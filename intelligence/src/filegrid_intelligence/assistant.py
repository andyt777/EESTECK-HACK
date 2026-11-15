"""Agent 03 - AI Operations Assistant (agentic RAG over FileGrid data, docs and Agent 02 findings).

Two providers behind the same tools:
  - AnthropicProvider: Claude with tool use (ANTHROPIC_API_KEY, ASSISTANT_MODEL);
  - SimulatedProvider: deterministic intent routing on Romanian keywords, calls the SAME real tools and
    composes the answer only from their results (provider = "SIMULATED").
Every tool is read-only; the assistant never writes, never invents data and always cites its sources.
"""

from __future__ import annotations

import contextlib
import json
import logging
import re
import time
from collections.abc import Callable
from dataclasses import dataclass, field

from .docs_index import DocsIndex, normalise

log = logging.getLogger(__name__)

UUID_RE = re.compile(r"\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b", re.I)
MAX_TOOL_CHARS = 6000
MAX_STEPS = 6

REFUSAL_PII = ("Nu pot furniza date personale (de exemplu CNP, adrese, telefoane sau date de identificare ale "
               "clienților). Documentele de racordare sunt criptate, iar asistentul nu are acces la conținutul lor. "
               "Pentru acces legitim folosiți fluxul de audit din FileGrid.")
REFUSAL_WRITE = ("Asistentul are doar acces de citire: nu șterge, nu modifică și nu creează nimic. Pot să vă arăt "
                 "datele relevante, iar acțiunea o faceți dumneavoastră din interfață.")
PII_PATTERNS = ("cnp", "cod numeric personal", "buletin", "carte de identitate", "serie ci", "iban", "parola",
                "password", "numar de telefon", "adresa clientului", "adresa de domiciliu")
WRITE_PATTERNS = ("sterge", "șterge", "delete", "elimina fisier", "modifica politica", "drop table", "rm -rf")

SYSTEM_PROMPT = """Ești PPCastor, castorul-electrician (cască albă, vestă reflectorizantă, căști cu microfon) care \
este asistentul AI de operațiuni al FileGrid PPC, platforma de gestiune a fișierelor unui operator de distribuție a \
energiei electrice (citiri contoare, înregistrări de avarie, documente de racordare, video-uri dronă). Răspunzi mereu \
în limba română, concis și concret. Ton prietenos și scurt, fără glume despre date, incidente sau probleme de rețea. \
Dacă te întreabă cine ești, spui că ești PPCastor, asistentul care răspunde la întrebări.

Reguli:
- Tu nu rutezi fișiere: rutarea o face un motor de reguli determinist (fără LLM), pe care interfața îl arată tot ca \
PPCastor, în Village. Poți explica deciziile de rutare doar din datele returnate de instrumente (get_routing_decision).
- Nu folosi denumirile interne „Agent 01” și „Agent 02” în răspunsuri: spune „rutarea lui PPCastor” / „decizia de \
rutare” și „analiza rețelei” / „constatările din Grid Intelligence”, chiar dacă documentele sau instrumentele le folosesc.
- Folosește instrumentele (tools) pentru orice afirmație despre date FileGrid. Nu inventa cifre, fișiere, incidente \
sau constatări. Dacă un instrument întoarce eroare sau nu există date, spune explicit că informația nu este disponibilă.
- Separă clar „Din datele FileGrid” de „Cunoștințe generale”.
- Citează sursele: menționează id-ul fișierului, incidentului sau constatării și documentul din docs/ folosit.
- Analiza rețelei (constatările din Grid Intelligence) nu afirmă cauze: prezintă observații, dovezi, încrederea și \
verificarea umană necesară. Fă la fel.
- Nu expune date personale (CNP, adrese, telefoane). Refuză astfel de cereri.
- Nu execuți operații de scriere; poți doar propune (de exemplu „Vrei să creez un incident?”), acțiunea fiind \
făcută de un om din interfață.
- Datele generate de simulator sunt marcate SIMULATED; menționează asta când le folosești."""


@dataclass
class ToolResult:
    data: object
    summary: str
    citations: list[dict] = field(default_factory=list)


@dataclass
class Tool:
    name: str
    description: str
    schema: dict
    run: Callable[[dict], ToolResult]


def _items(value) -> list[dict]:
    if isinstance(value, list):
        return [v for v in value if isinstance(v, dict)]
    if isinstance(value, dict):
        for key in ("items", "content", "incidents", "findings", "results"):
            if isinstance(value.get(key), list):
                return [v for v in value[key] if isinstance(v, dict)]
    return []


def _collect_origins(value, found: set[str], depth: int = 0) -> None:
    """Every ``dataOrigin`` value present in a tool result (files, passports, meter stats, Agent 02 findings)."""
    if depth > 6:
        return
    if isinstance(value, dict):
        origin = value.get("dataOrigin")
        if isinstance(origin, str) and origin.upper() in ("REAL", "SIMULATED"):
            found.add(origin.upper())
        for v in value.values():
            if isinstance(v, dict | list):
                _collect_origins(v, found, depth + 1)
    elif isinstance(value, list):
        for v in value:
            _collect_origins(v, found, depth + 1)


def data_origin(found: set[str]) -> str | None:
    """SIMULATED as soon as one source is simulated; REAL only when every source with an origin is real."""
    if "SIMULATED" in found:
        return "SIMULATED"
    return "REAL" if "REAL" in found else None


def _err(value) -> str | None:
    return value.get("error") if isinstance(value, dict) else None


def _obj(props: dict, required: list[str] | None = None) -> dict:
    return {"type": "object", "properties": props, "required": required or [], "additionalProperties": False}


class Toolbox:
    """The tools the assistant may call. All read-only.

    ``permissions`` = the asking user's permissions (None = unrestricted, e.g. internal use). Api-backed tools
    are enforced by the api itself through the user's token; local sources (Agent 02 findings read from the
    database) are checked here against the same permission the HTTP endpoint requires.
    """

    def __init__(self, api, agent02, docs: DocsIndex, permissions: frozenset[str] | None = None):
        self.api = api
        self.agent02 = agent02
        self.docs = docs
        self.permissions = permissions
        #: dataOrigin values seen in the results of this toolbox's calls (one toolbox per conversation turn)
        self.origins: set[str] = set()
        s = {"type": "string"}
        self.tools: dict[str, Tool] = {t.name: t for t in [
            Tool("search_files", "Caută fișiere în FileGrid după text (nume, descriere, stație) și/sau categorie.",
                 _obj({"q": s, "category": {"type": "string", "enum": ["METER_READING", "FAULT_RECORD",
                                                                       "CONNECTION_DOCUMENT", "DRONE_VIDEO"]}}),
                 self.search_files),
            Tool("get_file_passport", "File Passport: metadate, stare, integritate, storage, istoric pentru un fișier.",
                 _obj({"fileId": s}, ["fileId"]), self.get_file_passport),
            Tool("get_routing_decision", "Decizia deterministă de rutare (motorul de reguli) pentru un fișier: "
                 "criterii, scoruri, alternative și motivele respingerii.",
                 _obj({"fileId": s}, ["fileId"]), self.get_routing_decision),
            Tool("list_incidents", "Lista incidentelor deschise (securitate, integritate, rețea, teren).",
                 _obj({"type": s}), self.list_incidents),
            Tool("get_incident", "Detaliul unui incident (cronologie, fișiere, notificări).",
                 _obj({"incidentId": s}, ["incidentId"]), self.get_incident),
            Tool("meter_stats", "Statistici ale citirilor de contoare (contoare active, citiri, dubluri respinse).",
                 _obj({}), self.meter_stats),
            Tool("storage_health", "Starea storage-urilor FTP, S3 și Database.", _obj({}), self.storage_health),
            Tool("grid_findings", "Constatările analizei rețelei (anomalii în rețea: consum zero, vârfuri, citiri lipsă, "
                 "valori repetate, tensiune scăzută la post, export nocturn, valori imposibile) și sumarul lor.",
                 _obj({"type": s, "region": s, "station": s, "limit": {"type": "integer"}}), self.grid_findings),
            Tool("category_summary", "Sumar pe categorii de fișiere: volume, stări, storage folosit.", _obj({}),
                 self.category_summary),
            Tool("search_docs", "Caută în documentația platformei (arhitectură, politici de rutare, UX, cerințe).",
                 _obj({"query": s}, ["query"]), self.search_docs),
        ]}

    # --- tool implementations ------------------------------------------------
    def search_files(self, args: dict) -> ToolResult:
        data = self.api.get("/files", {"q": args.get("q"), "category": args.get("category"), "size": 10,
                                       "excludeMeters": "true" if not args.get("category") else None})
        if _err(data):
            return ToolResult(data, f"căutarea de fișiere a eșuat ({_err(data)})")
        items = _items(data)
        cites = [{"label": f"Fișier {i.get('originalName') or i.get('name') or i.get('id')}",
                  "href": f"/files/{i.get('id')}"} for i in items[:5] if i.get("id")]
        total = data.get("total", len(items)) if isinstance(data, dict) else len(items)
        return ToolResult(data, f"{total} fișiere găsite", cites)

    def get_file_passport(self, args: dict) -> ToolResult:
        fid = str(args.get("fileId", ""))
        data = self.api.get(f"/files/{fid}/passport")
        if _err(data):
            return ToolResult(data, f"passport indisponibil ({_err(data)})")
        return ToolResult(data, "passport încărcat", [{"label": f"File Passport {fid[:8]}", "href": f"/files/{fid}"}])

    def get_routing_decision(self, args: dict) -> ToolResult:
        fid = str(args.get("fileId", ""))
        data = self.api.get(f"/files/{fid}/decision")
        if _err(data):
            return ToolResult(data, f"decizia nu este disponibilă ({_err(data)})")
        return ToolResult(data, "decizia de rutare încărcată",
                          [{"label": f"Decizia de rutare {fid[:8]}", "href": f"/files/{fid}#decision"}])

    def list_incidents(self, args: dict) -> ToolResult:
        data = self.api.get("/incidents", {"open": "true", "type": args.get("type"), "size": 20})
        if _err(data):
            return ToolResult(data, f"incidentele nu sunt disponibile ({_err(data)})")
        items = _items(data)
        cites = [{"label": f"Incident {i.get('code') or i.get('id')}", "href": f"/incidents/{i.get('id')}"}
                 for i in items[:8] if i.get("id")]
        return ToolResult(data, f"{len(items)} incidente deschise", cites)

    def get_incident(self, args: dict) -> ToolResult:
        iid = str(args.get("incidentId", ""))
        data = self.api.get(f"/incidents/{iid}")
        if _err(data):
            return ToolResult(data, f"incidentul nu este disponibil ({_err(data)})")
        inc = data.get("incident", data) if isinstance(data, dict) else {}
        return ToolResult(data, f"incident {inc.get('code', iid)} încărcat",
                          [{"label": f"Incident {inc.get('code', iid)}", "href": f"/incidents/{iid}"}])

    def meter_stats(self, _args: dict) -> ToolResult:
        data = self.api.get("/meters/stats")
        if _err(data):
            return ToolResult(data, f"statisticile contoarelor nu sunt disponibile ({_err(data)})")
        return ToolResult(data, "statistici contoare încărcate", [{"label": "Statistici contoare", "href": "/meters"}])

    def storage_health(self, _args: dict) -> ToolResult:
        data = self.api.get("/storage/health")
        if _err(data):
            return ToolResult(data, f"starea storage-urilor nu este disponibilă ({_err(data)})")
        return ToolResult(data, "starea storage-urilor încărcată", [{"label": "Starea storage", "href": "/performance"}])

    def category_summary(self, _args: dict) -> ToolResult:
        data = self.api.get("/categories/summary")
        if _err(data):
            return ToolResult(data, f"sumarul pe categorii nu este disponibil ({_err(data)})")
        return ToolResult(data, "sumar pe categorii încărcat", [{"label": "Categorii", "href": "/"}])

    def scoped(self, api, permissions: frozenset[str]) -> Toolbox:
        """A request-scoped toolbox acting as the asking user (same Agent 02 and docs index)."""
        return Toolbox(api, self.agent02, self.docs, permissions)

    def grid_findings(self, args: dict) -> ToolResult:
        if self.permissions is not None and "intelligence:read" not in self.permissions:
            return ToolResult({"error": "acces interzis: constatările analizei rețelei necesită permisiunea intelligence:read"},
                              "acces interzis la constatările analizei rețelei")
        if self.agent02 is None:
            return ToolResult({"error": "analiza rețelei indisponibilă"}, "analiza rețelei indisponibilă")
        try:
            summary = self.agent02.summary()
            findings = self.agent02.list_findings(type_=args.get("type"), region=args.get("region"),
                                                  station=args.get("station"), limit=int(args.get("limit") or 8))
        except Exception as exc:  # noqa: BLE001
            return ToolResult({"error": str(exc)}, "constatările nu sunt disponibile")
        slim = [{k: f[k] for k in ("id", "type", "typeLabel", "title", "meterId", "post", "station", "region",
                                   "window", "observations", "confidence", "humanVerificationRequired", "status",
                                   "incidentId", "dataOrigin")} for f in findings]
        cites = [{"label": f"Constatare {f['typeLabel']} – {f['meterId'] or f['post']}",
                  "href": f"/grid-intelligence?finding={f['id']}"} for f in findings[:5]]
        summary = {k: summary.get(k) for k in ("total", "byType", "byRegion", "byStatus", "metersAnalysed",
                                                "readingsAnalysed", "lastRun", "dataOrigin")}
        return ToolResult({"summary": summary, "findings": slim},
                          f"{summary.get('total', 0)} constatări în total, {len(slim)} afișate", cites)

    def search_docs(self, args: dict) -> ToolResult:
        hits = self.docs.search(str(args.get("query", "")), k=4)
        cites = [{"label": f"{h['source']} – {h['heading']}", "href": f"/{h['source']}"} for h in hits]
        return ToolResult(hits, f"{len(hits)} fragmente din documentație", cites)

    # --- dispatch ----------------------------------------------------------------
    def call(self, name: str, args: dict) -> ToolResult:
        tool = self.tools.get(name)
        if tool is None:
            return ToolResult({"error": f"instrument necunoscut {name}"}, "instrument necunoscut")
        try:
            result = tool.run(args or {})
            if not _err(result.data):
                _collect_origins(result.data, self.origins)
            return result
        except Exception as exc:  # noqa: BLE001 - a tool failure must not break the conversation
            log.exception("tool %s failed", name)
            return ToolResult({"error": str(exc)}, f"eroare: {exc.__class__.__name__}")

    def anthropic_tools(self) -> list[dict]:
        return [{"name": t.name, "description": t.description, "input_schema": t.schema} for t in self.tools.values()]


def guard(message: str) -> str | None:
    text = normalise(message)
    if any(p in text for p in PII_PATTERNS):
        return REFUSAL_PII
    if any(normalise(p) in text for p in WRITE_PATTERNS):
        return REFUSAL_WRITE
    return None


# ------------------------------------------------------------------ simulated provider
INTENTS: list[tuple[str, tuple[str, ...]]] = [
    ("meter_stats", ("contor", "contoar", "citir", "meter", "prosumator")),
    ("grid_findings", ("anomal", "constat", "finding", "tensiun", "agent 02", "retea", "post de transform",
                       "consum zero", "varf", "export")),
    ("list_incidents", ("incident", "integritat", "alert", "securitat", "urgent", "problem")),
    ("storage_health", ("stocare", "storage", "s3", "ftp", "baza de date", "database", "disponibil")),
    ("category_summary", ("categori", "volum", "cate fisiere", "statistic")),
    ("search_files", ("fisier", "document", "video", "drona", "avarie", "inregistrar", "caut")),
    ("get_routing_decision", ("decizi", "rutar", "de ce", "agent 01", "unde a fost")),
    ("search_docs", ("politic", "arhitectur", "cum functioneaza", "ce este", "explic", "regul", "criptar",
                     "tus", "sha", "bulkhead", "fallback")),
]
CATEGORY_WORDS = {"video": "DRONE_VIDEO", "drona": "DRONE_VIDEO", "avari": "FAULT_RECORD",
                  "racordare": "CONNECTION_DOCUMENT", "contract": "CONNECTION_DOCUMENT"}


def route_intents(message: str, context: dict | None = None) -> list[tuple[str, dict]]:
    """Deterministic plan: which tools to call, with which arguments, for a Romanian question."""
    text = normalise(message)
    context = context or {}
    plan: list[tuple[str, dict]] = []
    ids = UUID_RE.findall(message)
    file_id = context.get("fileId") or (ids[0] if ids and "incident" not in text else None)
    incident_id = context.get("incidentId") or (ids[0] if ids and "incident" in text else None)
    if context.get("findingId"):
        plan.append(("grid_findings", {"limit": 5}))
    if incident_id:
        plan.append(("get_incident", {"incidentId": incident_id}))
    if file_id:
        plan.append(("get_file_passport", {"fileId": file_id}))
        plan.append(("get_routing_decision", {"fileId": file_id}))
    for name, words in INTENTS:
        if any(w in text for w in words):
            if name == "get_routing_decision":
                if not file_id:
                    plan.append(("search_docs", {"query": message}))
                continue
            if name == "search_files":
                category = next((c for w, c in CATEGORY_WORDS.items() if w in text), None)
                quoted = re.findall(r"[\"„“']([^\"”']{2,60})[\"”']", message)
                plan.append((name, {"q": quoted[0] if quoted else None, "category": category}))
            elif name == "search_docs":
                plan.append((name, {"query": message}))
            else:
                plan.append((name, {}))
    if not plan:
        plan = [("search_docs", {"query": message}), ("grid_findings", {"limit": 3})]
    seen, unique = set(), []
    for name, args in plan:
        key = (name, json.dumps(args, sort_keys=True))
        if key not in seen:
            seen.add(key)
            unique.append((name, args))
    return unique[:MAX_STEPS]


def _fmt(value) -> str:
    if isinstance(value, float):
        return f"{value:.2f}".replace(".", ",")
    return str(value)


def compose(name: str, result: ToolResult) -> str:
    """One Romanian paragraph built ONLY from a tool result."""
    data = result.data
    if _err(data):
        return f"- {name}: informația nu este disponibilă acum ({_err(data)})."
    if name == "meter_stats" and isinstance(data, dict):
        keys = [(k, v) for k, v in data.items() if isinstance(v, int | float | str) and not isinstance(v, bool)]
        return "- Contoare (din datele FileGrid): " + "; ".join(f"{k} = {_fmt(v)}" for k, v in keys[:10]) + "."
    if name == "grid_findings" and isinstance(data, dict):
        s = data.get("summary", {})
        by_type = ", ".join(f"{k}: {v}" for k, v in (s.get("byType") or {}).items()) or "niciuna"
        lines = [f"- Analiza rețelei a parcurs {s.get('readingsAnalysed', 0)} citiri de la {s.get('metersAnalysed', 0)} "
                 f"contoare și are {s.get('total', 0)} constatări (pe tip: {by_type}). Datele sunt SIMULATED."]
        for f in data.get("findings", [])[:3]:
            lines.append(f"  • {f['title']} — {f['observations']} Încredere {_fmt(f['confidence'])}. "
                         f"Verificare necesară: {f['humanVerificationRequired']} (id {f['id'][:8]})")
        return "\n".join(lines)
    if name == "list_incidents":
        items = _items(data)
        if not items:
            return "- Nu există incidente deschise în FileGrid."
        rows = [f"  • {i.get('code', i.get('id'))}: {i.get('title')} ({i.get('severity')}, {i.get('status')})"
                for i in items[:5]]
        return f"- Incidente deschise: {len(items)}.\n" + "\n".join(rows)
    if name == "get_incident" and isinstance(data, dict):
        inc = data.get("incident", data)
        return (f"- Incidentul {inc.get('code')}: {inc.get('title')} — severitate {inc.get('severity')}, stare "
                f"{inc.get('status')}, certitudine {inc.get('certainty')}, zona {inc.get('zone') or '—'}.")
    if name == "search_files":
        items = _items(data)
        if not items:
            return "- Nu am găsit fișiere care să corespundă căutării."
        rows = [f"  • {i.get('originalName') or i.get('name')} ({i.get('category')}, {i.get('status')}, "
                f"storage {i.get('storage') or i.get('storageType') or '—'})" for i in items[:5]]
        return f"- Fișiere găsite: {data.get('total', len(items)) if isinstance(data, dict) else len(items)}.\n" + \
            "\n".join(rows)
    if name == "get_routing_decision" and isinstance(data, dict):
        chosen = data.get("chosenStorage") or data.get("storage") or data.get("primary") or data.get("decision")
        return (f"- Decizia de rutare a lui PPCastor (deterministă, după reguli): storage ales {chosen}. "
                "Detaliile complete sunt în Decision Explorer.")
    if name == "get_file_passport" and isinstance(data, dict):
        f = data.get("file", data)
        return (f"- Passport: {f.get('originalName') or f.get('name')}, stare {f.get('status')}, integritate "
                f"{f.get('integrity') or f.get('integrityStatus')}.")
    if name == "storage_health":
        items = _items(data) or ([{"name": k, "status": v} for k, v in data.items()] if isinstance(data, dict) else [])
        rows = [f"{i.get('storage') or i.get('name') or i.get('type')}: {i.get('status') or i.get('state')}"
                for i in items[:6]]
        return "- Starea storage-urilor: " + ("; ".join(rows) or "fără date") + "."
    if name == "category_summary":
        items = _items(data) or (data if isinstance(data, list) else [])
        rows = [f"{i.get('category')}: {i.get('count', i.get('files', '—'))}" for i in items[:6] if isinstance(i, dict)]
        return "- Categorii: " + ("; ".join(rows) or "fără date") + "."
    if name == "search_docs":
        if not data:
            return "- Nu am găsit informații relevante în documentație."
        top = data[0]
        snippet = re.sub(r"\s+", " ", top["text"])[:400]
        return f"- Din documentație ({top['source']} – {top['heading']}): {snippet}…"
    return f"- {result.summary}."


class SimulatedProvider:
    name = "SIMULATED"

    def __init__(self, toolbox: Toolbox):
        self.toolbox = toolbox

    def answer(self, message: str, history: list[dict], context: dict | None, toolbox: Toolbox | None = None) -> dict:
        toolbox = toolbox or self.toolbox
        steps, citations, parts = [], [], []
        for name, args in route_intents(message, context):
            result = toolbox.call(name, args)
            steps.append({"tool": name, "input": args, "outputSummary": result.summary})
            citations.extend(result.citations)
            parts.append(compose(name, result))
        answer = ("Răspuns generat fără model de limbaj (mod SIMULATED), numai din datele returnate de "
                  "instrumente:\n" + "\n".join(parts))
        return {"answer": answer, "steps": steps, "citations": citations}


class AnthropicProvider:
    name = "anthropic"

    def __init__(self, toolbox: Toolbox, api_key: str, model: str):
        import anthropic

        self.toolbox = toolbox
        self.model = model
        self.client = anthropic.Anthropic(api_key=api_key, timeout=60.0, max_retries=1)
        self.bad_request = anthropic.BadRequestError
        self.use_fallbacks = True

    def _create(self, messages: list[dict]):
        params = {"model": self.model, "max_tokens": 4096, "system": SYSTEM_PROMPT,
                  "tools": self.toolbox.anthropic_tools(), "messages": messages,
                  "output_config": {"effort": "low"}}
        if self.use_fallbacks:
            try:
                # Server-side refusal fallback (routes a declined request to another model in the same call).
                return self.client.beta.messages.create(**params, betas=["server-side-fallback-2026-07-01"],
                                                        fallbacks="default")
            except (TypeError, self.bad_request):
                log.info("server-side fallbacks not accepted, using the plain Messages API")
                self.use_fallbacks = False
        return self.client.messages.create(**params)

    def answer(self, message: str, history: list[dict], context: dict | None, toolbox: Toolbox | None = None) -> dict:
        toolbox = toolbox or self.toolbox
        messages = [{"role": h["role"], "content": str(h.get("content", ""))} for h in history[-10:]
                    if h.get("role") in ("user", "assistant") and h.get("content")]
        text = message
        if context:
            text += f"\n\n[Context din interfață: {json.dumps(context, ensure_ascii=False)}]"
        messages.append({"role": "user", "content": text})
        steps, citations = [], []
        response = None
        for _ in range(MAX_STEPS + 1):
            response = self._create(messages)
            if response.stop_reason == "refusal":
                return {"answer": "Nu pot răspunde la această cerere.", "steps": steps, "citations": citations}
            if response.stop_reason == "pause_turn":
                messages.append({"role": "assistant", "content": response.content})
                continue
            uses = [b for b in response.content if b.type == "tool_use"]
            if response.stop_reason != "tool_use" or not uses:
                break
            messages.append({"role": "assistant", "content": response.content})
            results = []
            for block in uses:
                args = block.input if isinstance(block.input, dict) else {}
                result = toolbox.call(block.name, args)
                steps.append({"tool": block.name, "input": args, "outputSummary": result.summary})
                citations.extend(result.citations)
                payload = json.dumps(result.data, ensure_ascii=False, default=str)[:MAX_TOOL_CHARS]
                results.append({"type": "tool_result", "tool_use_id": block.id, "content": payload,
                                "is_error": bool(_err(result.data))})
            messages.append({"role": "user", "content": results})
        answer = "\n".join(b.text for b in (response.content if response else []) if b.type == "text").strip()
        return {"answer": answer or "Nu am putut formula un răspuns.", "steps": steps, "citations": citations}


class OpenAICompatibleProvider:
    """
    Any OpenAI-compatible chat/completions endpoint with function calling:
    Google Gemini (https://generativelanguage.googleapis.com/v1beta/openai/),
    Ollama (http://host.docker.internal:11434/v1), and others.
    """

    def __init__(self, toolbox: Toolbox, base_url: str, api_key: str, model: str, name: str):
        import httpx

        self.toolbox = toolbox
        self.model = model
        self.name = name
        self.url = base_url.rstrip("/") + "/chat/completions"
        self.http = httpx.Client(timeout=90.0, headers={"Authorization": f"Bearer {api_key or 'none'}"})

    #: 429 (per-minute quota, e.g. a free Gemini key) and 503 (model overloaded) are retried briefly
    RETRY_STATUSES = (429, 503)
    RETRY_DELAYS = (2.0, 5.0, 10.0)
    MAX_RETRY_WAIT = 20.0

    def _tools(self) -> list[dict]:
        return [{"type": "function", "function": {"name": t.name, "description": t.description,
                                                  "parameters": t.schema}} for t in self.toolbox.tools.values()]

    def _post(self, body: dict, sleep: Callable[[float], None] = time.sleep):
        waited = 0.0
        for delay in (*self.RETRY_DELAYS, None):
            response = self.http.post(self.url, json=body)
            if response.status_code not in self.RETRY_STATUSES or delay is None:
                break
            with contextlib.suppress(ValueError):
                delay = max(delay, float(response.headers.get("retry-after", 0)))
            if waited + delay > self.MAX_RETRY_WAIT:
                break
            log.info("%s answered %s, retrying in %.0f s", self.name, response.status_code, delay)
            sleep(delay)
            waited += delay
        response.raise_for_status()
        return response

    def answer(self, message: str, history: list[dict], context: dict | None, toolbox: Toolbox | None = None) -> dict:
        toolbox = toolbox or self.toolbox
        messages: list[dict] = [{"role": "system", "content": SYSTEM_PROMPT}]
        messages += [{"role": h["role"], "content": str(h.get("content", ""))} for h in history[-10:]
                     if h.get("role") in ("user", "assistant") and h.get("content")]
        text = message
        if context:
            text += f"\n\n[Context din interfață: {json.dumps(context, ensure_ascii=False)}]"
        messages.append({"role": "user", "content": text})
        steps, citations = [], []
        content = ""
        for _ in range(MAX_STEPS + 1):
            response = self._post({"model": self.model, "messages": messages, "tools": self._tools(),
                                   "temperature": 0.2})
            choice = response.json()["choices"][0]["message"]
            content = choice.get("content") or ""
            calls = choice.get("tool_calls") or []
            if not calls:
                break
            messages.append({"role": "assistant", "content": content or None, "tool_calls": calls})
            for call in calls:
                fn = call.get("function", {})
                try:
                    args = json.loads(fn.get("arguments") or "{}")
                except json.JSONDecodeError:
                    args = {}
                args = args if isinstance(args, dict) else {}
                result = toolbox.call(fn.get("name", ""), args)
                steps.append({"tool": fn.get("name", ""), "input": args, "outputSummary": result.summary})
                citations.extend(result.citations)
                payload = json.dumps(result.data, ensure_ascii=False, default=str)[:MAX_TOOL_CHARS]
                messages.append({"role": "tool", "tool_call_id": call.get("id", fn.get("name", "")),
                                 "name": fn.get("name", ""), "content": payload})
        return {"answer": content.strip() or "Nu am putut formula un răspuns.", "steps": steps,
                "citations": citations}


class Assistant:
    def __init__(self, toolbox: Toolbox, api_key: str, model: str, llm_provider: str = "",
                 llm_base_url: str = "", llm_api_key: str = "", llm_model: str = ""):
        self.toolbox = toolbox
        self.simulated = SimulatedProvider(toolbox)
        self.llm = None
        provider = (llm_provider or ("anthropic" if api_key else "")).lower()
        try:
            if provider == "anthropic" and api_key:
                self.llm = AnthropicProvider(toolbox, api_key, model)
            elif provider in ("gemini", "ollama", "openai"):
                defaults = {"gemini": "https://generativelanguage.googleapis.com/v1beta/openai/",
                            "ollama": "http://host.docker.internal:11434/v1"}
                base = llm_base_url or defaults.get(provider, "")
                if base and (llm_api_key or provider == "ollama"):
                    self.llm = OpenAICompatibleProvider(toolbox, base, llm_api_key, llm_model, provider)
        except Exception:  # noqa: BLE001
            log.exception("LLM provider %s unavailable, using SIMULATED", provider)
        log.info("assistant provider: %s", self.llm.name if self.llm else "SIMULATED")

    def chat(self, message: str, history: list[dict] | None = None, context: dict | None = None,
             toolbox: Toolbox | None = None) -> dict:
        """``toolbox``: the request-scoped toolbox acting as the asking user (see ``Toolbox.scoped``)."""
        history = history or []
        refusal = guard(message)
        if refusal:
            return {"answer": refusal, "steps": [], "citations": [], "dataOrigin": None,
                    "provider": self.llm.name if self.llm else "SIMULATED", "refused": True}
        # A fresh toolbox for this turn, so the data origin reflects only what this answer read.
        base = toolbox or self.toolbox
        toolbox = Toolbox(base.api, base.agent02, base.docs, base.permissions)
        provider, note = self.simulated, None
        result = None
        if self.llm is not None:
            try:
                result = self.llm.answer(message, history, context, toolbox)
                provider = self.llm
            except Exception as exc:  # noqa: BLE001 - fall back to the deterministic provider
                log.warning("%s call failed, using SIMULATED: %s", self.llm.name, exc)
                note = "Modelul de limbaj nu a răspuns; s-a folosit modul SIMULATED."
        if result is None:
            result = self.simulated.answer(message, history, context, toolbox)
        unique, seen = [], set()
        for c in result["citations"]:
            if c["href"] not in seen:
                seen.add(c["href"])
                unique.append(c)
        out = {**result, "citations": unique, "dataOrigin": data_origin(toolbox.origins), "provider": provider.name}
        if note:
            out["note"] = note
        return out
