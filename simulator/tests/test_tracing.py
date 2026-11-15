import httpx
import pytest
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import SimpleSpanProcessor
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter
from opentelemetry.trace import StatusCode

from filegrid_simulator import tracing
from filegrid_simulator.client import FileGridClient


async def no_sleep(_seconds):
    return None


@pytest.fixture
def spans(monkeypatch):
    """A real SDK provider with an in-memory exporter, used only by the client's tracer."""
    exporter = InMemorySpanExporter()
    provider = TracerProvider()
    provider.add_span_processor(SimpleSpanProcessor(exporter))
    monkeypatch.setattr(tracing, "tracer", lambda: provider.get_tracer("test"))
    yield exporter
    provider.shutdown()


def test_tracing_is_on_only_with_an_endpoint_and_not_disabled():
    assert not tracing.enabled_by_env({})
    assert not tracing.enabled_by_env({"OTEL_EXPORTER_OTLP_ENDPOINT": "  "})
    assert tracing.enabled_by_env({"OTEL_EXPORTER_OTLP_ENDPOINT": "http://alloy:4318"})
    assert not tracing.enabled_by_env(
        {"OTEL_EXPORTER_OTLP_ENDPOINT": "http://alloy:4318", "OTEL_SDK_DISABLED": "TRUE"}
    )


async def test_every_attempt_carries_the_traceparent_of_the_batch_span(spans):
    seen = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request.headers.get("traceparent"))
        if len(seen) < 2:
            return httpx.Response(503)
        return httpx.Response(202, json={"accepted": ["a"], "duplicate": []})

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as http:
        await FileGridClient(http, "http://filegrid.test/api", sleep=no_sleep).send([{"meterId": "M1"}])

    [span] = spans.get_finished_spans()
    trace_id = format(span.context.trace_id, "032x")
    span_id = format(span.context.span_id, "016x")
    assert len(seen) == 2
    for header in seen:
        version, header_trace, header_span, flags = header.split("-")
        assert (version, header_trace, header_span) == ("00", trace_id, span_id)
        assert int(flags, 16) & 0x01  # sampled; newer SDKs also set the random-trace-id bit (0x02)
    assert span.name == "simulator.send_batch"
    assert span.attributes["filegrid.attempts"] == 2
    assert span.attributes["filegrid.accepted"] == 1
    assert span.status.status_code != StatusCode.ERROR


async def test_a_refused_batch_marks_the_span_as_error(spans):
    async with httpx.AsyncClient(transport=httpx.MockTransport(lambda _r: httpx.Response(400))) as http:
        client = FileGridClient(http, "http://filegrid.test/api", sleep=no_sleep)
        result = await client.send([{"meterId": "M1"}])

    assert result.rejected
    [span] = spans.get_finished_spans()
    assert span.status.status_code == StatusCode.ERROR


async def test_without_a_provider_no_traceparent_is_sent():
    seen = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request.headers.get("traceparent"))
        return httpx.Response(202, json={"accepted": ["a"], "duplicate": []})

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as http:
        await FileGridClient(http, "http://filegrid.test/api", sleep=no_sleep).send([{"meterId": "M1"}])

    assert seen == [None]
