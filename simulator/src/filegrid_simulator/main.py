"""Simulator main loop.

Every tick: build one reading per meter for the next period, send them in
batches (at most BATCH_SIZE, CONCURRENCY batches at a time), wait until every
batch is acknowledged, then mark the tick done in output/state.json. After a
restart the simulator continues from the first tick that was not fully
acknowledged, so readings already sent may be sent again: FileGrid reports
them as duplicates and stores nothing twice.
"""

from __future__ import annotations

import asyncio
import contextlib
import dataclasses
import json
import logging
import random
import signal
import time
from collections import deque
from datetime import datetime
from pathlib import Path

import httpx
from prometheus_client import start_http_server

from . import metrics
from .anomalies import AnomalyPlan, iso, load_specs
from .client import FileGridClient
from .config import Settings, load
from .generator import ReadingGenerator
from .topology import build_meters, load_topology

log = logging.getLogger("filegrid_simulator")
RECENT_ACKED = 5000


class State:
    """Progress kept across restarts (output/state.json)."""

    def __init__(self, path: Path, settings: Settings):
        self.path = path
        self.next_tick = 0
        self.sim_start = settings.sim_start
        if path.exists():
            data = json.loads(path.read_text(encoding="utf-8"))
            same_run = (
                data.get("seed") == settings.seed
                and data.get("meters") == settings.meters
                and data.get("intervalMinutes") == settings.interval_minutes
            )
            if same_run:
                self.next_tick = int(data.get("nextTick", 0))
                self.sim_start = datetime.fromisoformat(data["simStart"].replace("Z", "+00:00"))
                log.info("Continuing from tick %d (simulated start %s)", self.next_tick, data["simStart"])
        self.settings = settings

    def save(self, next_tick: int) -> None:
        self.next_tick = next_tick
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(
            json.dumps(
                {
                    "seed": self.settings.seed,
                    "meters": self.settings.meters,
                    "intervalMinutes": self.settings.interval_minutes,
                    "simStart": iso(self.sim_start),
                    "nextTick": next_tick,
                }
            ),
            encoding="utf-8",
        )
        tmp.replace(self.path)


def burst_factor(settings: Settings, elapsed: float) -> float:
    """MODE=storm: x10 during the first STORM_BURST_SECONDS of every STORM_BURST_EVERY_SECONDS."""
    if settings.mode != "storm":
        return 1.0
    return 10.0 if elapsed % settings.storm_burst_every_seconds < settings.storm_burst_seconds else 1.0


def with_duplicates(
    readings: list[dict], recent: deque, rate: float, rng: random.Random
) -> tuple[list[dict], int]:
    """Adds ~rate of readings already acknowledged earlier, to show that FileGrid rejects them."""
    if not recent or rate <= 0:
        return readings, 0
    extra = [recent[rng.randrange(len(recent))] for _ in readings if rng.random() < rate]
    return readings + extra, len(extra)


async def run_async(
    settings: Settings, stop: asyncio.Event | None = None, transport: httpx.AsyncBaseTransport | None = None
) -> int:
    stop = stop or asyncio.Event()
    topology = load_topology(settings.topology_path)
    meters = build_meters(topology, settings.meters, settings.prosumer_share, settings.seed)
    state = State(settings.output_dir / "state.json", settings)
    settings = dataclasses.replace(settings, sim_start=state.sim_start)
    plan = AnomalyPlan(load_specs(settings.anomalies_path), meters, settings.seed)
    generator = ReadingGenerator(plan, settings.interval_minutes, settings.seed)
    truth_path = settings.output_dir / "injected_anomalies.json"
    rng = random.Random(settings.seed * 7919)
    recent: deque = deque(maxlen=RECENT_ACKED)

    metrics.TARGET_RPM.set(settings.readings_per_minute)
    log.info(
        "Simulator %s: %d meters (%d prosumers), %.0f readings/min, batches of %d -> %s",
        settings.mode,
        len(meters),
        sum(m.prosumer for m in meters),
        settings.readings_per_minute,
        settings.batch_size,
        settings.api_url,
    )

    timeout = httpx.Timeout(settings.request_timeout_seconds, connect=5.0)
    limits = httpx.Limits(
        max_connections=settings.concurrency, max_keepalive_connections=settings.concurrency
    )
    started = time.monotonic()
    tick = state.next_tick
    ticks_done = 0
    async with httpx.AsyncClient(timeout=timeout, limits=limits, transport=transport) as http:
        client = FileGridClient(http, settings.api_url, settings.max_retry_backoff_seconds)
        semaphore = asyncio.Semaphore(settings.concurrency)
        next_at = time.monotonic()
        while not stop.is_set():
            elapsed = time.monotonic() - started
            if settings.duration_seconds is not None and elapsed >= settings.duration_seconds:
                break
            factor = burst_factor(settings, elapsed)
            metrics.RATE_MULTIPLIER.set(settings.rate_multiplier * settings.time_scale * factor)

            period_start, _ = settings.period(tick)
            known_before = plan.known
            readings = []
            for meter in meters:
                reading = generator.reading(meter, tick, period_start)
                if reading is None:
                    metrics.MISSING.inc()
                else:
                    readings.append(reading)
            if plan.known != known_before:
                plan.write(truth_path, settings.period, settings.sim_start, settings.interval_minutes)

            batches = [
                readings[i : i + settings.batch_size] for i in range(0, len(readings), settings.batch_size)
            ]
            prepared = []
            for batch in batches:
                with_dupes, extra = with_duplicates(batch, recent, settings.duplicate_rate, rng)
                metrics.DUPLICATES_SENT.inc(extra)
                prepared.append(with_dupes[:500])

            async def send(batch: list[dict]):
                async with semaphore:
                    return await client.send(batch, stop)

            try:
                results = await asyncio.gather(*(send(b) for b in prepared))
            except asyncio.CancelledError:
                log.info("Stopped before tick %d was acknowledged; it will be sent again on restart", tick)
                break
            for batch, result in zip(batches, results, strict=True):
                if not result.rejected:
                    recent.extend(batch)
            state.save(tick + 1)
            metrics.TICK.set(tick)
            metrics.SIM_TIME.set(period_start.timestamp())
            tick += 1
            ticks_done += 1

            next_at += settings.tick_seconds / factor
            delay = next_at - time.monotonic()
            if delay < -settings.tick_seconds:
                next_at = time.monotonic()  # far behind (FileGrid slow or down): do not burst to catch up
            elif delay > 0:
                with contextlib.suppress(TimeoutError):
                    await asyncio.wait_for(stop.wait(), timeout=delay)
    plan.write(truth_path, settings.period, settings.sim_start, settings.interval_minutes)
    log.info("Simulator stopped after %d tick(s)", ticks_done)
    return ticks_done


def run(argv: list[str] | None = None) -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s - %(message)s")
    logging.getLogger("httpx").setLevel(logging.WARNING)  # one line per request is too much at 300 req/min
    settings = load(argv)
    start_http_server(settings.metrics_port)

    async def main() -> None:
        stop = asyncio.Event()
        loop = asyncio.get_running_loop()
        for sig in (signal.SIGTERM, signal.SIGINT):
            with contextlib.suppress(NotImplementedError):  # not available on Windows
                loop.add_signal_handler(sig, stop.set)
        await run_async(settings, stop)

    asyncio.run(main())


if __name__ == "__main__":
    run()
