"""Sends batches to FileGrid until they are acknowledged.

A batch keeps the same Idempotency-Key and exactly the same body across all
its attempts, so a retry after a timeout can never create a second copy:
FileGrid answers with the stored result of the first attempt.
"""

from __future__ import annotations

import asyncio
import logging
import random
import time
import uuid
from dataclasses import dataclass

import httpx
from opentelemetry.trace import SpanKind, Status, StatusCode

from . import metrics, tracing
from .auth import ClientCredentialsToken, TokenUnavailable

log = logging.getLogger(__name__)
RETRYABLE_STATUS = {408, 425, 429, 500, 502, 503, 504}


@dataclass(frozen=True)
class BatchResult:
    key: str
    accepted: int
    duplicate: int
    attempts: int
    rejected: bool = False


class FileGridClient:
    def __init__(
        self,
        http: httpx.AsyncClient,
        api_url: str,
        max_backoff_seconds: float = 30.0,
        sleep=asyncio.sleep,
        tokens: ClientCredentialsToken | None = None,
    ):
        self.http = http
        self.url = api_url.rstrip("/") + "/meters/readings"
        self.max_backoff = max_backoff_seconds
        self.sleep = sleep
        self.tokens = tokens  # None = no Authorization header (local runs without Keycloak)

    async def send(self, readings: list[dict], stop: asyncio.Event | None = None) -> BatchResult:
        key = f"sim-{uuid.uuid4()}"
        attributes = {"filegrid.batch_key": key, "filegrid.readings": len(readings), "http.url": self.url}
        with tracing.tracer().start_as_current_span(
            "simulator.send_batch", kind=SpanKind.CLIENT, attributes=attributes
        ) as span:
            result = await self._send(key, readings, stop)
            span.set_attribute("filegrid.attempts", result.attempts)
            span.set_attribute("filegrid.accepted", result.accepted)
            span.set_attribute("filegrid.duplicate", result.duplicate)
            if result.rejected:
                span.set_status(Status(StatusCode.ERROR, "batch refused by FileGrid"))
            return result

    async def _send(self, key: str, readings: list[dict], stop: asyncio.Event | None) -> BatchResult:
        body = {"readings": readings}
        attempt = 0
        auth_retried = False
        started = time.monotonic()
        while True:
            attempt += 1
            metrics.SENT.inc(len(readings))
            if attempt > 1:
                metrics.RETRIED.inc()
            try:
                headers = tracing.inject({"Idempotency-Key": key})
                if self.tokens is not None:
                    headers["Authorization"] = f"Bearer {await self.tokens.get()}"
                response = await self.http.post(self.url, json=body, headers=headers)
            except (httpx.HTTPError, TokenUnavailable) as error:
                log.warning("Batch %s attempt %d failed: %s", key, attempt, error)
            else:
                if response.status_code == 401 and self.tokens is not None and not auth_retried:
                    # Token expired or revoked early: get a fresh one and retry once, without backoff.
                    auth_retried = True
                    self.tokens.invalidate()
                    log.warning("Batch %s attempt %d answered 401; retrying with a new token", key, attempt)
                    continue
                if response.status_code == 202:
                    payload = response.json()
                    accepted, duplicate = len(payload.get("accepted", [])), len(payload.get("duplicate", []))
                    metrics.ACKED.inc(len(readings))
                    metrics.ACCEPTED.inc(accepted)
                    metrics.DUPLICATES_REPORTED.inc(duplicate)
                    metrics.BATCH_SECONDS.observe(time.monotonic() - started)
                    return BatchResult(key, accepted, duplicate, attempt)
                if response.status_code not in RETRYABLE_STATUS:
                    metrics.REJECTED.inc()
                    log.error("Batch %s refused with %d: %s", key, response.status_code, response.text[:500])
                    return BatchResult(key, 0, 0, attempt, rejected=True)
                log.warning("Batch %s attempt %d answered %d; retrying", key, attempt, response.status_code)
            if stop is not None and stop.is_set() and attempt >= 3:
                # Shutting down: the tick is not marked done, so it is sent again on restart.
                raise asyncio.CancelledError(f"batch {key} not acknowledged before shutdown")
            await self.sleep(self.backoff(attempt))

    def backoff(self, attempt: int) -> float:
        base = min(self.max_backoff, 0.5 * 2 ** (attempt - 1))
        return base * (0.5 + random.random() * 0.5)
