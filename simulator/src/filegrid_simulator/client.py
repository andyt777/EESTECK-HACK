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

from . import metrics

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
        self, http: httpx.AsyncClient, api_url: str, max_backoff_seconds: float = 30.0, sleep=asyncio.sleep
    ):
        self.http = http
        self.url = api_url.rstrip("/") + "/meters/readings"
        self.max_backoff = max_backoff_seconds
        self.sleep = sleep

    async def send(self, readings: list[dict], stop: asyncio.Event | None = None) -> BatchResult:
        key = f"sim-{uuid.uuid4()}"
        body = {"readings": readings}
        attempt = 0
        started = time.monotonic()
        while True:
            attempt += 1
            metrics.SENT.inc(len(readings))
            if attempt > 1:
                metrics.RETRIED.inc()
            try:
                response = await self.http.post(self.url, json=body, headers={"Idempotency-Key": key})
            except httpx.HTTPError as error:
                log.warning("Batch %s attempt %d failed: %s", key, attempt, error)
            else:
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
