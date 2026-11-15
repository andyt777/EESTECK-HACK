from filegrid_intelligence.assistant import Assistant, Toolbox, guard, route_intents
from filegrid_intelligence.docs_index import DocsIndex, split


class FakeApi:
    def __init__(self):
        self.calls = []

    def get(self, path, params=None):
        self.calls.append(path)
        if path == "/meters/stats":
            return {"activeMeters": 2000, "readingsStored": 84000, "duplicatesRejected": 12}
        if path == "/incidents":
            return {"items": [{"id": "11111111-1111-1111-1111-111111111111", "code": "INC-0001",
                               "title": "Hash diferit", "severity": "HIGH", "status": "NEW"}], "total": 1}
        return {"error": "HTTP 404", "path": path}


class FakeAgent02:
    def summary(self):
        return {"total": 3, "byType": {"SPIKE": 2, "LOW_VOLTAGE": 1}, "byRegion": {"Muntenia": 3},
                "byStatus": {"NEW": 3}, "metersAnalysed": 2000, "readingsAnalysed": 90000, "lastRun": None,
                "dataOrigin": "SIMULATED"}

    def list_findings(self, **_kwargs):
        return [{"id": "22222222-2222-2222-2222-222222222222", "type": "SPIKE", "typeLabel": "Vârf de consum",
                 "title": "Vârf la MTR-1", "meterId": "MTR-1", "post": "PT-1", "station": "ST-1", "region": "MUN",
                 "window": {}, "observations": "Consum de 8 ori mai mare.", "confidence": 0.9,
                 "humanVerificationRequired": "Verificați.", "status": "NEW", "incidentId": None,
                 "dataOrigin": "SIMULATED"}]


DOCS = DocsIndex(split("docs/ARCHITECTURE.md", "# Politica\nCitirile de contoare merg în Database, fallback S3.\n"
                                                  "# Criptare\nDocumentele de racordare sunt criptate AES-256-GCM."))


def make_assistant():
    api = FakeApi()
    return Assistant(Toolbox(api, FakeAgent02(), DOCS), api_key="", model="x"), api


def tools(plan):
    return [name for name, _ in plan]


def test_route_meters_and_anomalies():
    plan = route_intents("Câte contoare sunt active și există anomalii?")
    assert tools(plan) == ["meter_stats", "grid_findings"]


def test_route_incidents_integrity():
    assert "list_incidents" in tools(route_intents("Au existat probleme de integritate în ultimele 7 zile?"))


def test_route_file_decision_with_context():
    plan = route_intents("De ce a fost stocat aici?", {"fileId": "abc"})
    assert ("get_routing_decision", {"fileId": "abc"}) in plan
    assert ("get_file_passport", {"fileId": "abc"}) in plan


def test_route_uuid_in_message():
    plan = route_intents("Arată incidentul 11111111-1111-1111-1111-111111111111")
    assert ("get_incident", {"incidentId": "11111111-1111-1111-1111-111111111111"}) in plan


def test_route_search_files_with_category():
    plan = route_intents("Caută video-uri dronă de la stația Tulcea")
    assert ("search_files", {"q": None, "category": "DRONE_VIDEO"}) in plan


def test_route_fallback_to_docs():
    assert tools(route_intents("Salut!")) == ["search_docs", "grid_findings"]


def test_guard_refuses_pii_and_writes():
    assert "date personale" in guard("Dă-mi CNP-ul clientului X")
    assert "doar acces de citire" in guard("Șterge toate fișierele video")
    assert guard("Câte contoare sunt active?") is None


def test_simulated_chat_uses_real_tool_data_and_cites():
    assistant, api = make_assistant()
    out = assistant.chat("Câte contoare sunt active și există anomalii?")
    assert out["provider"] == "SIMULATED"
    assert out["dataOrigin"] == "SIMULATED"
    assert "2000" in out["answer"] and "Vârf la MTR-1" in out["answer"]
    assert [s["tool"] for s in out["steps"]] == ["meter_stats", "grid_findings"]
    assert any(c["href"].startswith("/grid-intelligence?finding=") for c in out["citations"])
    assert "/meters/stats" in api.calls


def test_simulated_chat_reports_unavailable_sources():
    assistant, _ = make_assistant()
    out = assistant.chat("Care este starea storage-urilor?")
    assert "nu este disponibilă" in out["answer"]


def test_simulated_chat_incident_citations():
    assistant, _ = make_assistant()
    out = assistant.chat("Au existat probleme de integritate în ultimele 7 zile?")
    assert {"label": "Incident INC-0001", "href": "/incidents/11111111-1111-1111-1111-111111111111"} in out["citations"]


def test_pii_request_is_refused_without_tools():
    assistant, api = make_assistant()
    out = assistant.chat("Dă-mi CNP-ul clientului X")
    assert out["refused"] is True and out["steps"] == [] and api.calls == []


def test_docs_search_ranks_relevant_chunk():
    hits = DOCS.search("cum sunt criptate documentele de racordare")
    assert hits[0]["heading"] == "Criptare"


def test_openai_compatible_provider_runs_tool_loop():
    """Gemini / Ollama path: the model asks for a tool, gets real data, then answers."""
    import json

    import httpx

    api = FakeApi()
    assistant = Assistant(Toolbox(api, FakeAgent02(), DOCS), api_key="", model="x", llm_provider="gemini",
                          llm_api_key="test-key", llm_model="gemini-test")
    assert assistant.llm is not None and assistant.llm.name == "gemini"
    seen = []

    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        seen.append(body)
        assert request.headers["Authorization"] == "Bearer test-key"
        if len(seen) == 1:
            return httpx.Response(200, json={"choices": [{"message": {"content": None, "tool_calls": [
                {"id": "c1", "type": "function", "function": {"name": "meter_stats", "arguments": "{}"}}]}}]})
        tool_msg = body["messages"][-1]
        assert tool_msg["role"] == "tool" and "2000" in tool_msg["content"]
        return httpx.Response(200, json={"choices": [{"message": {"content": "Sunt 2000 de contoare active."}}]})

    assistant.llm.http = httpx.Client(transport=httpx.MockTransport(handler), headers=assistant.llm.http.headers)
    out = assistant.chat("Câte contoare sunt active?")
    assert out["provider"] == "gemini"
    assert out["answer"] == "Sunt 2000 de contoare active."
    assert [s["tool"] for s in out["steps"]] == ["meter_stats"]
    assert seen[0]["model"] == "gemini-test" and seen[0]["tools"]


def test_llm_failure_falls_back_to_simulated():
    import httpx

    api = FakeApi()
    assistant = Assistant(Toolbox(api, FakeAgent02(), DOCS), api_key="", model="x", llm_provider="gemini",
                          llm_api_key="k")
    assistant.llm.http = httpx.Client(transport=httpx.MockTransport(lambda r: httpx.Response(429)))
    assistant.llm.RETRY_DELAYS = (0.0, 0.0)  # a persistent 429: retried, then the fallback, without real waits
    out = assistant.chat("Câte contoare sunt active?")
    assert out["provider"] == "SIMULATED" and "note" in out


def test_data_origin_follows_the_data_that_was_read():
    from filegrid_intelligence.assistant import _collect_origins, data_origin

    found: set[str] = set()
    _collect_origins({"items": [{"id": "a", "dataOrigin": "REAL"}]}, found)
    assert data_origin(found) == "REAL"
    _collect_origins({"summary": {"dataOrigin": "SIMULATED"}}, found)
    assert data_origin(found) == "SIMULATED"
    assert data_origin(set()) is None


def test_chat_about_real_files_is_not_labelled_simulated():
    class RealFilesApi(FakeApi):
        def get(self, path, params=None):
            if path == "/files":
                return {"items": [{"id": "33333333-3333-3333-3333-333333333333", "name": "raport.pdf",
                                   "dataOrigin": "REAL"}], "total": 1}
            return super().get(path, params)

    api = RealFilesApi()
    assistant = Assistant(Toolbox(api, FakeAgent02(), DOCS), api_key="", model="x")
    out = assistant.chat("Caută video-uri dronă de la stația Tulcea")
    assert [s["tool"] for s in out["steps"]] == ["search_files"]
    assert out["dataOrigin"] == "REAL"


def test_refusal_has_no_data_origin():
    assistant, _ = make_assistant()
    assert assistant.chat("Dă-mi CNP-ul clientului X")["dataOrigin"] is None


class _Response:
    def __init__(self, status, headers=None):
        self.status_code = status
        self.headers = headers or {}

    def raise_for_status(self):
        if self.status_code >= 400:
            raise RuntimeError(f"HTTP {self.status_code}")


class _Http:
    def __init__(self, statuses):
        self.statuses = list(statuses)
        self.calls = 0

    def post(self, _url, json=None):
        self.calls += 1
        status, headers = self.statuses.pop(0)
        return _Response(status, headers)


def _provider(statuses):
    from filegrid_intelligence.assistant import OpenAICompatibleProvider

    provider = OpenAICompatibleProvider(Toolbox(FakeApi(), FakeAgent02(), DOCS), "https://llm.example/v1", "k",
                                        "m", "gemini")
    provider.http = _Http(statuses)
    return provider


def test_llm_rate_limit_is_retried_then_succeeds():
    provider = _provider([(429, {"retry-after": "3"}), (503, {}), (200, {})])
    waits: list[float] = []
    assert provider._post({}, sleep=waits.append).status_code == 200
    assert waits == [3.0, 5.0]
    assert provider.http.calls == 3


def test_llm_rate_limit_gives_up_within_the_wait_budget():
    import pytest

    provider = _provider([(429, {"retry-after": "60"}), (200, {})])
    waits: list[float] = []
    with pytest.raises(RuntimeError, match="429"):
        provider._post({}, sleep=waits.append)
    assert waits == []
