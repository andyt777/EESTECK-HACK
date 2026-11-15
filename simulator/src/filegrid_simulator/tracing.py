"""OpenTelemetry tracing of the simulator (Stage 8).

Every batch is one CLIENT span ("simulator.send_batch") whose W3C ``traceparent`` header goes with the
HTTP request, so the trace continues in the api (OpenTelemetry Java agent), through RabbitMQ, to the
worker and the storage. Spans are exported over OTLP/HTTP to Alloy, which forwards them to Tempo.

Tracing is on only when ``OTEL_EXPORTER_OTLP_ENDPOINT`` is set and ``OTEL_SDK_DISABLED`` is not
``true``; otherwise the OpenTelemetry API stays a no-op and the simulator behaves exactly the same.
"""

from __future__ import annotations

import logging
import os
from collections.abc import Mapping

from opentelemetry import propagate, trace

log = logging.getLogger(__name__)
TRACER_NAME = "filegrid_simulator"
_provider = None


def enabled_by_env(env: Mapping[str, str] | None = None) -> bool:
    values: Mapping[str, str] = os.environ if env is None else env
    if values.get("OTEL_SDK_DISABLED", "").strip().lower() == "true":
        return False
    return bool(values.get("OTEL_EXPORTER_OTLP_ENDPOINT", "").strip())


def setup() -> bool:
    """Installs the SDK tracer provider with an OTLP exporter. Returns whether tracing is on."""
    global _provider
    if _provider is not None:
        return True
    if not enabled_by_env():
        return False
    # Imported only when tracing is on: the SDK and the exporter are not needed otherwise.
    from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
    from opentelemetry.sdk.resources import Resource
    from opentelemetry.sdk.trace import TracerProvider
    from opentelemetry.sdk.trace.export import BatchSpanProcessor

    resource = Resource.create({"service.name": os.environ.get("OTEL_SERVICE_NAME", "simulator")})
    provider = TracerProvider(resource=resource)
    # The exporter reads OTEL_EXPORTER_OTLP_ENDPOINT itself and appends /v1/traces.
    provider.add_span_processor(BatchSpanProcessor(OTLPSpanExporter()))
    trace.set_tracer_provider(provider)
    _provider = provider
    log.info("Tracing on: spans go to %s", os.environ["OTEL_EXPORTER_OTLP_ENDPOINT"])
    return True


def shutdown() -> None:
    """Flushes the spans still buffered (called when the simulator stops)."""
    global _provider
    if _provider is not None:
        _provider.shutdown()
        _provider = None


def tracer() -> trace.Tracer:
    return trace.get_tracer(TRACER_NAME)


def inject(headers: dict[str, str]) -> dict[str, str]:
    """Adds the W3C trace context of the current span (``traceparent``) to the HTTP headers."""
    propagate.inject(headers)
    return headers
