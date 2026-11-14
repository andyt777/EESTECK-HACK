import asyncio
import dataclasses
import json

import httpx

from filegrid_simulator.client import FileGridClient
from filegrid_simulator.main import run_async, with_duplicates


async def no_sleep(_seconds):
    return None


def accept_all(request: httpx.Request) -> httpx.Response:
    readings = json.loads(request.content)["readings"]
    ids = [f"{r['meterId']}|{r['periodStart']}" for r in readings]
    return httpx.Response(202, json={"accepted": ids, "duplicate": []})


async def test_retries_with_the_same_key_and_body_until_acknowledged():
    seen = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append((request.headers["Idempotency-Key"], request.content))
        if len(seen) < 3:
            return httpx.Response(503)
        return httpx.Response(202, json={"accepted": ["a"], "duplicate": ["b"]})

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as http:
        result = await FileGridClient(http, "http://filegrid.test/api", sleep=no_sleep).send(
            [{"meterId": "M1"}]
        )
    assert result.attempts == 3 and result.accepted == 1 and result.duplicate == 1
    assert len({key for key, _ in seen}) == 1
    assert len({body for _, body in seen}) == 1
    assert seen[0][0].startswith("sim-")


async def test_a_refused_batch_is_not_retried():
    calls = []

    def handler(request):
        calls.append(request)
        return httpx.Response(400, json={"errors": ["bad"]})

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as http:
        result = await FileGridClient(http, "http://filegrid.test/api", sleep=no_sleep).send(
            [{"meterId": "M1"}]
        )
    assert result.rejected and len(calls) == 1


async def test_network_errors_are_retried():
    calls = []

    def handler(request):
        calls.append(request)
        if len(calls) == 1:
            raise httpx.ConnectError("down", request=request)
        return httpx.Response(202, json={"accepted": ["x"], "duplicate": []})

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as http:
        result = await FileGridClient(http, "http://filegrid.test/api", sleep=no_sleep).send(
            [{"meterId": "M1"}]
        )
    assert result.attempts == 2 and not result.rejected


def test_duplicates_are_added_at_about_the_configured_rate():
    import random
    from collections import deque

    recent = deque([{"meterId": f"old-{i}"} for i in range(100)])
    batch = [{"meterId": f"new-{i}"} for i in range(10_000)]
    out, extra = with_duplicates(batch, recent, 0.01, random.Random(1))
    assert out[:10_000] == batch
    assert 60 <= extra <= 140 and len(out) == 10_000 + extra
    assert all(r["meterId"].startswith("old-") for r in out[10_000:])


async def test_loop_sends_every_meter_once_per_tick_and_resumes_after_restart(settings):
    requests = []

    def handler(request):
        requests.append(json.loads(request.content)["readings"])
        return accept_all(request)

    settings = dataclasses.replace(settings, duration_seconds=None)
    stop = asyncio.Event()

    async def stop_after(ticks):
        while sum(len(r) for r in requests) < ticks * settings.meters:
            await asyncio.sleep(0.01)
        stop.set()

    transport = httpx.MockTransport(handler)
    await asyncio.gather(run_async(settings, stop, transport), stop_after(3))
    readings = [r for batch in requests for r in batch]
    per_tick = {}
    for r in readings:
        per_tick.setdefault(r["seq"], set()).add(r["meterId"])
    assert set(per_tick) >= {1, 2, 3}
    assert all(len(per_tick[s]) == settings.meters for s in (1, 2, 3))
    assert all(len(batch) <= settings.batch_size for batch in requests)
    state = json.loads((settings.output_dir / "state.json").read_text())
    assert state["nextTick"] >= 3

    # Restart: continues from the first tick not yet acknowledged, same simulated start.
    requests.clear()
    stop = asyncio.Event()
    await asyncio.gather(run_async(settings, stop, transport), stop_after(1))
    first_seq = min(r["seq"] for batch in requests for r in batch)
    assert first_seq == state["nextTick"] + 1
    assert (settings.output_dir / "injected_anomalies.json").exists()
