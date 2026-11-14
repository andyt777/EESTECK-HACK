"""FastAPI app: /api/intelligence/** (Agent 02, assistant, voice). Exposed only through the gateway."""

from __future__ import annotations

import logging
import threading
from contextlib import asynccontextmanager
from pathlib import Path

import uvicorn
from fastapi import APIRouter, FastAPI, HTTPException, Query
from prometheus_client import Counter, Gauge, make_asgi_app
from pydantic import BaseModel, Field

from .agent02 import GridIntelligence
from .api_client import FileGridApi
from .assistant import Assistant, Toolbox
from .config import Settings
from .db import Database
from .docs_index import DocsIndex
from .topology import load_topology
from .voice import VoiceService, make_provider, parse_field_report

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
log = logging.getLogger("filegrid_intelligence")

RUNS = Counter("filegrid_intelligence_analysis_runs_total", "Agent 02 analysis runs", ["result"])
FINDINGS = Gauge("filegrid_intelligence_detections", "Detections in the last Agent 02 run")
CHATS = Counter("filegrid_intelligence_assistant_requests_total", "Assistant requests", ["provider"])


class State:
    settings: Settings
    db: Database
    agent02: GridIntelligence
    assistant: Assistant
    voice: VoiceService
    topology = None
    stop = threading.Event()


state = State()


def _analysis_loop() -> None:
    while not state.stop.is_set():
        try:
            result = state.agent02.run("scheduled")
            FINDINGS.set(result["detections"])
            RUNS.labels("ok").inc()
        except Exception as exc:  # noqa: BLE001 - keep the loop alive
            RUNS.labels("error").inc()
            log.warning("analysis failed: %s", exc)
        state.stop.wait(state.settings.analysis_interval_seconds)


@asynccontextmanager
async def lifespan(_app: FastAPI):
    settings = Settings.from_env()
    state.settings = settings
    state.topology = load_topology(settings.topology_path)
    state.db = Database(settings.meta_db_dsn, settings.store_db_dsn)
    state.db.open()
    api = FileGridApi(settings.api_url)
    state.agent02 = GridIntelligence(settings, state.db, state.topology, api)
    roots = {Path(p): Path(p).name for p in settings.docs_dirs}
    docs = DocsIndex.build(settings.docs_dirs, roots)
    log.info("docs index: %d chunks", len(docs.chunks))
    state.assistant = Assistant(Toolbox(api, state.agent02, docs), settings.anthropic_api_key,
                                settings.assistant_model, settings.llm_provider, settings.llm_base_url,
                                settings.llm_api_key, settings.llm_model)
    state.voice = VoiceService(state.db, api, make_provider(settings))
    state.stop.clear()
    thread = threading.Thread(target=_analysis_loop, name="agent02", daemon=True)
    thread.start()
    yield
    state.stop.set()
    state.db.close()


app = FastAPI(title="FileGrid intelligence", lifespan=lifespan, docs_url="/api/intelligence/docs",
              openapi_url="/api/intelligence/openapi.json")
app.mount("/metrics", make_asgi_app())
router = APIRouter(prefix="/api/intelligence")


def _db_or_503():
    if not state.db.ensure_schema():
        raise HTTPException(503, "Baza de date meta-db nu este disponibilă.")


@app.get("/health")
def health():
    return {"status": "UP", "schema": state.db.schema_ready, "lastRun": state.agent02.last_run}


@router.get("/health")
def api_health():
    return health()


# ------------------------------------------------------------------ Agent 02
@router.get("/findings")
def findings(type: str | None = None, status: str | None = None, region: str | None = None,  # noqa: A002
             station: str | None = None, post: str | None = None, meterId: str | None = None,  # noqa: N803
             limit: int = Query(50, ge=1, le=500)):
    _db_or_503()
    items = state.agent02.list_findings(type, status, region, station, post, meterId, limit)
    return {"items": items, "count": len(items), "dataOrigin": "SIMULATED"}


@router.get("/findings/{finding_id}")
def finding(finding_id: str):
    _db_or_503()
    item = state.agent02.get_finding(finding_id)
    if item is None:
        raise HTTPException(404, "Constatarea nu există.")
    return item


class StatusBody(BaseModel):
    status: str
    note: str | None = None


@router.post("/findings/{finding_id}/status")
def finding_status(finding_id: str, body: StatusBody):
    status = body.status.upper()
    if status not in ("NEW", "CONFIRMED", "DISMISSED"):
        raise HTTPException(400, "Stare invalidă: folosiți NEW, CONFIRMED sau DISMISSED.")
    _db_or_503()
    item = state.agent02.set_status(finding_id, status, body.note)
    if item is None:
        raise HTTPException(404, "Constatarea nu există.")
    return item


@router.get("/summary")
def summary():
    _db_or_503()
    return state.agent02.summary()


@router.get("/grid")
def grid():
    _db_or_503()
    return state.agent02.grid()


@router.post("/analyze")
def analyze():
    try:
        result = state.agent02.run("on-demand")
    except Exception as exc:  # noqa: BLE001
        RUNS.labels("error").inc()
        raise HTTPException(503, f"Analiza nu a putut rula: {exc}") from exc
    RUNS.labels("ok").inc()
    FINDINGS.set(result["detections"])
    return result


@router.get("/evaluation")
def evaluation():
    return state.agent02.evaluation()


# ------------------------------------------------------------------ assistant
class ChatContext(BaseModel):
    fileId: str | None = None  # noqa: N815
    incidentId: str | None = None  # noqa: N815
    findingId: str | None = None  # noqa: N815


class ChatBody(BaseModel):
    message: str = Field(min_length=1, max_length=4000)
    history: list[dict] = Field(default_factory=list)
    context: ChatContext | None = None


@router.post("/assistant/chat")
def chat(body: ChatBody):
    context = body.context.model_dump(exclude_none=True) if body.context else None
    result = state.assistant.chat(body.message, body.history, context)
    CHATS.labels(result["provider"]).inc()
    return result


# ------------------------------------------------------------------ voice
class VoiceCallBody(BaseModel):
    incidentId: str  # noqa: N815
    role: str = "FIELD_TEAM"
    message: str | None = None


@router.post("/voice/call")
def voice_call(body: VoiceCallBody):
    _db_or_503()
    return state.voice.call(body.incidentId, body.role, body.message)


@router.get("/voice/calls")
def voice_calls(incidentId: str | None = None):  # noqa: N803
    _db_or_503()
    items = state.voice.list(incidentId)
    return {"items": items, "count": len(items)}


class FieldReportBody(BaseModel):
    transcript: str = Field(min_length=3, max_length=5000)
    zone: str | None = None
    station: str | None = None
    create: bool = False
    dataOrigin: str = "SIMULATED"  # noqa: N815


@router.post("/voice/field-report")
def field_report(body: FieldReportBody):
    proposal = parse_field_report(body.transcript, body.zone, body.station, state.topology)
    proposal["dataOrigin"] = "REAL" if body.dataOrigin.upper() == "REAL" else "SIMULATED"
    out = {"proposal": proposal, "created": False}
    if body.create:
        api = state.agent02.api
        payload = {k: v for k, v in proposal.items() if k != "parser"}
        result = api.post("/incidents", payload)
        if isinstance(result, dict) and result.get("error"):
            out["error"] = f"Incidentul nu a putut fi creat: {result['error']}"
        else:
            inc = result.get("incident", result) if isinstance(result, dict) else {}
            out.update(created=True, incidentId=str(inc.get("id")) if inc.get("id") else None,
                       incidentCode=inc.get("code"))
    return out


app.include_router(router)


def run() -> None:
    settings = Settings.from_env()
    uvicorn.run("filegrid_intelligence.main:app", host="0.0.0.0", port=settings.port, log_level="info",
                access_log=False)
