"""Stage 11: /api/me-backed authorization, token forwarding in tools, service token refresh."""

import base64
import json
import logging
import time

import httpx
import pytest
from fastapi.testclient import TestClient

from filegrid_intelligence import auth, main
from filegrid_intelligence.api_client import FileGridApi
from filegrid_intelligence.assistant import Assistant, Toolbox
from filegrid_intelligence.auth import Authenticator, AuthUnavailable, ClientCredentialsToken, token_exp

from .test_assistant import DOCS, FakeAgent02

API = "http://api.test/api"
ALL_READ = ["intelligence:read", "assistant:use", "meters:read", "incident:read"]


def jwt(exp: float | None = None, sub: str = "u") -> str:
    def b64(obj) -> str:
        return base64.urlsafe_b64encode(json.dumps(obj).encode()).decode().rstrip("=")

    claims = {"sub": sub}
    if exp is not None:
        claims["exp"] = int(exp)
    return f"{b64({'alg': 'RS256'})}.{b64(claims)}.sig"


class MeServer:
    """Fake api: /me answers per token, other paths record the Authorization header."""

    def __init__(self, users: dict[str, list[str]]):
        self.users = users
        self.me_calls = 0
        self.down = False
        self.seen: list[tuple[str, str | None]] = []

    def __call__(self, request: httpx.Request) -> httpx.Response:
        if self.down:
            raise httpx.ConnectError("down", request=request)
        token = request.headers.get("authorization", "").removeprefix("Bearer ")
        if request.url.path == "/api/me":
            self.me_calls += 1
            if token not in self.users:
                return httpx.Response(401)
            return httpx.Response(200, json={"actor": f"actor-{len(token)}", "roles": ["x"],
                                             "permissions": self.users[token]})
        self.seen.append((request.url.path, request.headers.get("authorization")))
        if request.url.path == "/api/meters/stats":
            return httpx.Response(200, json={"activeMeters": 7, "readingsStored": 70})
        if request.url.path == "/api/incidents" and request.method == "POST":
            return httpx.Response(201, json={"id": "inc-1", "code": "INC-1"})
        return httpx.Response(404)


# ------------------------------------------------------------------ Authenticator unit tests
def test_token_exp_reads_payload_without_verification():
    assert token_exp(jwt(exp=1234)) == 1234
    assert token_exp("not-a-jwt") is None
    assert token_exp("a.!!!.c") is None


def test_valid_token_resolves_to_principal_and_is_cached():
    token = jwt(exp=time.time() + 300)
    server = MeServer({token: ["intelligence:read"]})
    authn = Authenticator(API, transport=httpx.MockTransport(server))
    first = authn.authenticate(token)
    second = authn.authenticate(token)
    assert first is not None and first.can("intelligence:read") and not first.can("intelligence:write")
    assert second == first
    assert server.me_calls == 1  # cache hit
    assert token not in repr(first)


def test_cache_expires_with_the_token():
    now = [1000.0]
    token = jwt(exp=1100)
    server = MeServer({token: ["intelligence:read"]})
    authn = Authenticator(API, transport=httpx.MockTransport(server), clock=lambda: now[0])
    assert authn.authenticate(token) is not None
    now[0] = 1099
    assert authn.authenticate(token) is not None
    assert server.me_calls == 1
    now[0] = 1100  # expired: refused without asking the api
    assert authn.authenticate(token) is None
    assert server.me_calls == 1


def test_invalid_token_is_not_cached():
    server = MeServer({})
    authn = Authenticator(API, transport=httpx.MockTransport(server))
    token = jwt(exp=time.time() + 300)
    assert authn.authenticate(token) is None
    assert authn.authenticate(token) is None
    assert server.me_calls == 2


@pytest.mark.parametrize("failure", ["network", "500"])
def test_api_unavailable_raises(failure):
    def handler(request):
        if failure == "network":
            raise httpx.ConnectError("down", request=request)
        return httpx.Response(500)

    authn = Authenticator(API, transport=httpx.MockTransport(handler))
    with pytest.raises(AuthUnavailable):
        authn.authenticate(jwt(exp=time.time() + 300))


# ------------------------------------------------------------------ HTTP layer (FastAPI)
class FakeDb:
    schema_ready = True

    def ensure_schema(self):
        return True


class FakeAgent02WithApi(FakeAgent02):
    last_run = None

    def __init__(self, api):
        self.api = api

    def summary(self):
        return {**super().summary(), "ok": True}


@pytest.fixture
def app_env():
    """The app without lifespan (no DB, no threads): fakes wired into main.state, api mocked."""
    read_token = jwt(exp=time.time() + 300, sub="reader")
    field_token = jwt(exp=time.time() + 300, sub="field-user")
    voice_token = jwt(exp=time.time() + 300, sub="voice-only")
    server = MeServer({read_token: ALL_READ,
                       field_token: ["assistant:use", "voice:use", "incident:create"],
                       voice_token: ["voice:use"]})
    transport = httpx.MockTransport(server)
    api = FileGridApi(API, transport=transport)
    main.state.api = api
    main.state.db = FakeDb()
    main.state.agent02 = FakeAgent02WithApi(api)
    main.state.assistant = Assistant(Toolbox(api, main.state.agent02, DOCS), api_key="", model="x")
    main.state.topology = None
    auth.configure(Authenticator(API, transport=transport))
    client = TestClient(main.app)
    yield client, server, {"read": read_token, "field": field_token, "voice": voice_token}
    auth._authenticator = None


def bearer(token: str) -> dict:
    return {"Authorization": f"Bearer {token}"}


def test_health_endpoints_stay_public(app_env):
    client, _, _ = app_env
    assert client.get("/health").status_code == 200
    assert client.get("/api/intelligence/health").status_code == 200


def test_missing_or_malformed_token_is_401(app_env):
    client, _, _ = app_env
    resp = client.get("/api/intelligence/summary")
    assert resp.status_code == 401 and resp.headers["www-authenticate"] == "Bearer"
    assert client.get("/api/intelligence/summary", headers={"Authorization": "Basic abc"}).status_code == 401
    assert client.get("/api/intelligence/summary", headers=bearer("unknown")).status_code == 401


def test_permission_allows_and_denies(app_env):
    client, _, tokens = app_env
    assert client.get("/api/intelligence/summary", headers=bearer(tokens["read"])).status_code == 200
    denied = client.get("/api/intelligence/summary", headers=bearer(tokens["field"]))
    assert denied.status_code == 403 and "intelligence:read" in denied.json()["detail"]
    # intelligence:write is required for writes even with intelligence:read
    resp = client.post("/api/intelligence/findings/x/status", json={"status": "CONFIRMED"},
                       headers=bearer(tokens["read"]))
    assert resp.status_code == 403
    assert client.post("/api/intelligence/analyze", headers=bearer(tokens["read"])).status_code == 403
    assert client.post("/api/intelligence/assistant/chat", json={"message": "salut"},
                       headers=bearer(tokens["voice"])).status_code == 403


def test_every_non_health_route_is_protected(app_env):
    client, _, _ = app_env
    for route in main.router.routes:
        if route.path.endswith("/health"):
            continue
        method = sorted(route.methods)[0]
        path = route.path.replace("{finding_id}", "x")
        resp = client.request(method, path, json={})
        assert resp.status_code == 401, f"{method} {path} answered {resp.status_code}"


def test_api_down_is_503(app_env):
    client, server, tokens = app_env
    server.down = True
    resp = client.get("/api/intelligence/summary", headers=bearer(tokens["read"]))
    assert resp.status_code == 503


def test_unconfigured_authenticator_fails_closed(app_env):
    client, _, tokens = app_env
    auth._authenticator = None
    assert client.get("/api/intelligence/summary", headers=bearer(tokens["read"])).status_code == 503


def test_assistant_tools_forward_the_users_token_and_never_echo_it(app_env, caplog):
    client, server, tokens = app_env
    caplog.set_level(logging.DEBUG)
    resp = client.post("/api/intelligence/assistant/chat", json={"message": "Câte contoare sunt active?"},
                       headers=bearer(tokens["read"]))
    assert resp.status_code == 200
    assert ("/api/meters/stats", f"Bearer {tokens['read']}") in server.seen
    assert "70" in resp.json()["answer"]
    assert tokens["read"] not in resp.text
    assert tokens["read"] not in caplog.text


def test_assistant_hides_findings_without_intelligence_read(app_env):
    client, _, tokens = app_env
    resp = client.post("/api/intelligence/assistant/chat", json={"message": "Există anomalii în rețea?"},
                       headers=bearer(tokens["field"]))
    assert resp.status_code == 200
    steps = {s["tool"]: s["outputSummary"] for s in resp.json()["steps"]}
    assert steps.get("grid_findings") == "acces interzis la constatările analizei rețelei"
    assert "Vârf la MTR-1" not in resp.text


def test_field_report_creates_incident_as_the_user(app_env):
    client, server, tokens = app_env
    body = {"transcript": "Cablu căzut pe stradă, pericol", "create": True}
    resp = client.post("/api/intelligence/voice/field-report", json=body, headers=bearer(tokens["field"]))
    assert resp.status_code == 200 and resp.json()["created"] is True
    assert ("/api/incidents", f"Bearer {tokens['field']}") in server.seen
    # voice:use alone may only get a proposal, not create an incident
    denied = client.post("/api/intelligence/voice/field-report", json=body, headers=bearer(tokens["voice"]))
    assert denied.status_code == 403
    ok = client.post("/api/intelligence/voice/field-report", json={**body, "create": False},
                     headers=bearer(tokens["voice"]))
    assert ok.status_code == 200 and ok.json()["created"] is False


def test_x_actor_header_is_gone():
    seen = []

    def handler(request):
        seen.append(request.headers)
        return httpx.Response(200, json={})

    FileGridApi(API, transport=httpx.MockTransport(handler)).get("/meters/stats")
    assert "x-actor" not in seen[0] and "authorization" not in seen[0]


# ------------------------------------------------------------------ service token (client_credentials)
class TokenServer:
    def __init__(self, expires_in: int = 300):
        self.issued = 0
        self.expires_in = expires_in
        self.forms: list[str] = []

    def __call__(self, request: httpx.Request) -> httpx.Response:
        self.issued += 1
        self.forms.append(request.content.decode())
        return httpx.Response(200, json={"access_token": f"svc-{self.issued}", "expires_in": self.expires_in,
                                         "token_type": "Bearer"})


def test_service_token_is_cached_and_refreshed_before_expiry():
    now = [0.0]
    server = TokenServer(expires_in=300)
    source = ClientCredentialsToken("http://kc.test/token", "filegrid-intelligence", "s3cret",
                                    transport=httpx.MockTransport(server), clock=lambda: now[0])
    assert source.get() == "svc-1"
    now[0] = 269
    assert source.get() == "svc-1" and server.issued == 1
    now[0] = 270  # 30 s before expiry -> refresh
    assert source.get() == "svc-2" and server.issued == 2
    assert "grant_type=client_credentials" in server.forms[0]
    assert "client_id=filegrid-intelligence" in server.forms[0]
    assert "s3cret" not in repr(source)


def test_service_calls_retry_once_on_401_with_a_fresh_token():
    server = TokenServer()
    source = ClientCredentialsToken("http://kc.test/token", "filegrid-intelligence", "s3cret",
                                    transport=httpx.MockTransport(server))
    seen = []

    def api_handler(request):
        seen.append(request.headers.get("authorization"))
        if request.headers.get("authorization") == "Bearer svc-1":
            return httpx.Response(401)
        return httpx.Response(201, json={"id": "inc-9"})

    api = FileGridApi(API, transport=httpx.MockTransport(api_handler), token_source=source)
    assert api.create_incident({"fingerprint": "f"}) == "inc-9"
    assert seen == ["Bearer svc-1", "Bearer svc-2"]


def test_service_calls_do_not_loop_on_persistent_401():
    server = TokenServer()
    source = ClientCredentialsToken("http://kc.test/token", "c", "s", transport=httpx.MockTransport(server))
    calls = []

    def api_handler(request):
        calls.append(1)
        return httpx.Response(401)

    api = FileGridApi(API, transport=httpx.MockTransport(api_handler), token_source=source)
    assert api.get("/incidents") == {"error": "HTTP 401", "path": "/incidents"}
    assert len(calls) == 2


def test_user_token_is_never_refreshed_on_401():
    calls = []

    def handler(request):
        calls.append(request.headers.get("authorization"))
        return httpx.Response(401)

    api = FileGridApi(API, transport=httpx.MockTransport(handler)).with_token("user-tok")
    assert api.get("/files")["error"] == "HTTP 401"
    assert calls == ["Bearer user-tok"]


def test_token_endpoint_down_means_unauthenticated_call_not_crash():
    def handler(request):
        raise httpx.ConnectError("down", request=request)

    source = ClientCredentialsToken("http://kc.test/token", "c", "s", transport=httpx.MockTransport(handler))
    assert source.get() is None
    assert ClientCredentialsToken("http://kc.test/token", "c", "").get() is None


def test_transcribe_endpoint_permissions_and_states(app_env):
    client, _, tokens = app_env

    class FakeTranscriber:
        def transcribe(self, audio, content_type):
            assert audio == b"OggS" and content_type.startswith("audio/ogg")
            return "avarie la postul PT-DOB-04-03"

    url = "/api/intelligence/voice/transcribe"
    ogg = {"Content-Type": "audio/ogg"}
    main.state.transcriber = None
    assert client.post(url, content=b"OggS", headers={**ogg, **bearer(tokens["read"])}).status_code == 403
    assert client.post(url, content=b"OggS", headers={**ogg, **bearer(tokens["voice"])}).status_code == 503
    main.state.transcriber = FakeTranscriber()
    try:
        bad = client.post(url, content=b"x", headers={"Content-Type": "image/jpeg", **bearer(tokens["voice"])})
        assert bad.status_code == 415
        ok = client.post(url, content=b"OggS", headers={**ogg, **bearer(tokens["voice"])})
        assert ok.status_code == 200 and ok.json() == {"transcript": "avarie la postul PT-DOB-04-03"}
    finally:
        main.state.transcriber = None
