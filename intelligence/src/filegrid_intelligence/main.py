"""FastAPI app: /api/intelligence/** (Agent 02, assistant, voice). Exposed only through the gateway.

Every route except the health checks requires a permission resolved through the api's ``GET /me``
(docs/AUTH-DESIGN.md §3); assistant and voice act on the api with the asking user's own token."""

from __future__ import annotations

import logging
import threading
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Annotated

import uvicorn
from fastapi import APIRouter, Depends, FastAPI, HTTPException, Query, Request
from fastapi.concurrency import run_in_threadpool
from prometheus_client import Counter, Gauge, make_asgi_app
from pydantic import BaseModel, Field

from . import auth
from .agent02 import GridIntelligence
from .api_client import FileGridApi
from .assistant import Assistant, Toolbox
from .auth import Authenticator, ClientCredentialsToken, Principal, require
from .config import Settings
from .db import Database
from .docs_index import DocsIndex
from .topology import load_topology
from .voice import GeminiTranscriber, TranscriptionError, VoiceService, make_provider, make_transcriber, parse_field_report

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
    transcriber: GeminiTranscriber | None = None
    api: FileGridApi
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
    auth.configure(Authenticator(settings.api_url))
    service_token = ClientCredentialsToken(settings.kc_token_url, settings.kc_client_id, settings.kc_client_secret)
    # Service identity: Agent 02 loop and its incidents. User-facing routes use api.with_token(...).
    api = FileGridApi(settings.api_url, token_source=service_token)
    state.api = api
    state.agent02 = GridIntelligence(settings, state.db, state.topology, api)
    roots = {Path(p): Path(p).name for p in settings.docs_dirs}
    docs = DocsIndex.build(settings.docs_dirs, roots)
    log.info("docs index: %d chunks", len(docs.chunks))
    state.assistant = Assistant(Toolbox(api, state.agent02, docs), settings.anthropic_api_key,
                                settings.assistant_model, settings.llm_provider, settings.llm_base_url,
                                settings.llm_api_key, settings.llm_model)
    state.voice = VoiceService(state.db, api, make_provider(settings))
    state.transcriber = make_transcriber(settings)
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
READ = [Depends(require("intelligence:read"))]
WRITE = [Depends(require("intelligence:write"))]
AssistantUser = Annotated[Principal, Depends(require("assistant:use"))]
VoiceUser = Annotated[Principal, Depends(require("voice:use"))]


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
@router.get("/findings", dependencies=READ)
def findings(type: str | None = None, status: str | None = None, region: str | None = None,  # noqa: A002
             station: str | None = None, post: str | None = None, meterId: str | None = None,  # noqa: N803
             limit: int = Query(50, ge=1, le=500)):
    _db_or_503()
    items = state.agent02.list_findings(type, status, region, station, post, meterId, limit)
    return {"items": items, "count": len(items), "dataOrigin": "SIMULATED"}


@router.get("/findings/{finding_id}", dependencies=READ)
def finding(finding_id: str):
    _db_or_503()
    item = state.agent02.get_finding(finding_id)
    if item is None:
        raise HTTPException(404, "Constatarea nu există.")
    return item


class StatusBody(BaseModel):
    status: str
    note: str | None = None


@router.post("/findings/{finding_id}/status", dependencies=WRITE)
def finding_status(finding_id: str, body: StatusBody):
    status = body.status.upper()
    if status not in ("NEW", "CONFIRMED", "DISMISSED"):
        raise HTTPException(400, "Stare invalidă: folosiți NEW, CONFIRMED sau DISMISSED.")
    _db_or_503()
    item = state.agent02.set_status(finding_id, status, body.note)
    if item is None:
        raise HTTPException(404, "Constatarea nu există.")
    return item


@router.get("/summary", dependencies=READ)
def summary():
    _db_or_503()
    return state.agent02.summary()


@router.get("/grid", dependencies=READ)
def grid():
    _db_or_503()
    return state.agent02.grid()


@router.post("/analyze", dependencies=WRITE)
def analyze():
    try:
        result = state.agent02.run("on-demand")
    except Exception as exc:  # noqa: BLE001
        RUNS.labels("error").inc()
        raise HTTPException(503, f"Analiza nu a putut rula: {exc}") from exc
    RUNS.labels("ok").inc()
    FINDINGS.set(result["detections"])
    return result


@router.get("/evaluation", dependencies=READ)
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
def chat(body: ChatBody, principal: AssistantUser):
    context = body.context.model_dump(exclude_none=True) if body.context else None
    # Tools act as the asking user: the api enforces exactly what this user may see.
    toolbox = state.assistant.toolbox.scoped(state.api.with_token(principal.token), principal.permissions)
    result = state.assistant.chat(body.message, body.history, context, toolbox=toolbox)
    CHATS.labels(result["provider"]).inc()
    return result


# ------------------------------------------------------------------ voice
class VoiceCallBody(BaseModel):
    incidentId: str  # noqa: N815
    role: str = "FIELD_TEAM"
    message: str | None = None


@router.post("/voice/call")
def voice_call(body: VoiceCallBody, principal: VoiceUser):
    _db_or_503()
    return state.voice.call(body.incidentId, body.role, body.message, api=state.api.with_token(principal.token))


@router.get("/voice/calls", dependencies=READ)
def voice_calls(incidentId: str | None = None):  # noqa: N803
    _db_or_503()
    items = state.voice.list(incidentId)
    return {"items": items, "count": len(items)}


@router.post("/voice/transcribe")
async def transcribe(request: Request, principal: VoiceUser):
    """Voice note (raw audio body, e.g. audio/ogg from WhatsApp) -> transcript. Read with a 16 MB cap."""
    transcriber = state.transcriber
    if transcriber is None:
        raise HTTPException(503, "Transcrierea vocală nu este configurată (necesită o cheie Gemini).")
    content_type = request.headers.get("content-type", "")
    if not content_type.lower().startswith("audio/"):
        raise HTTPException(415, "Trimiteți un mesaj audio (Content-Type audio/*).")
    audio = bytearray()
    async for chunk in request.stream():
        audio.extend(chunk)
        if len(audio) > GeminiTranscriber.MAX_BYTES:
            raise HTTPException(413, "Mesajul audio depășește 16 MB.")
    try:
        transcript = await run_in_threadpool(transcriber.transcribe, bytes(audio), content_type)
    except TranscriptionError as e:
        raise HTTPException(422, str(e)) from e
    return {"transcript": transcript}


class FieldReportBody(BaseModel):
    transcript: str = Field(min_length=3, max_length=5000)
    zone: str | None = None
    station: str | None = None
    create: bool = False
    dataOrigin: str = "SIMULATED"  # noqa: N815


@router.post("/voice/field-report")
def field_report(body: FieldReportBody, principal: VoiceUser):
    if body.create and not principal.can("incident:create"):
        raise HTTPException(403, "Nu aveți permisiunea necesară (incident:create).")
    proposal = parse_field_report(body.transcript, body.zone, body.station, state.topology)
    proposal["dataOrigin"] = "REAL" if body.dataOrigin.upper() == "REAL" else "SIMULATED"
    out = {"proposal": proposal, "created": False}
    if body.create:
        # The incident is created as the asking user (audit shows who reported it), not as the service.
        api = state.api.with_token(principal.token)
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
