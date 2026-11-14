"""Injectable anomalies and their ground truth (output/injected_anomalies.json)."""

from __future__ import annotations

import json
import random
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path

import yaml

from .topology import Meter

TYPES = (
    "ZERO_CONSUMPTION",
    "SPIKE",
    "MISSING_READINGS",
    "REPEATED_VALUES",
    "LOW_VOLTAGE",
    "NIGHT_EXPORT",
    "IMPOSSIBLE_VALUES",
)
PER_TRANSFORMER = {"LOW_VOLTAGE"}


@dataclass(frozen=True)
class AnomalySpec:
    type: str
    count: int
    start_tick: int
    duration_ticks: int
    repeat_every_ticks: int = 0
    params: dict = field(default_factory=dict)


@dataclass(frozen=True)
class Occurrence:
    id: str
    type: str
    first_tick: int
    last_tick: int
    meter_ids: tuple[str, ...]
    transformer_ids: tuple[str, ...]
    params: dict

    def active(self, tick: int) -> bool:
        return self.first_tick <= tick <= self.last_tick


def load_specs(path: Path) -> list[AnomalySpec]:
    if not path.exists():
        return []
    with open(path, encoding="utf-8") as handle:
        raw = yaml.safe_load(handle) or {}
    specs = []
    for item in raw.get("anomalies", []):
        kind = str(item["type"]).upper()
        if kind not in TYPES:
            raise ValueError(f"Unknown anomaly type {kind!r} in {path}; allowed: {', '.join(TYPES)}")
        count = int(item.get("transformers" if kind in PER_TRANSFORMER else "meters", 1))
        params = {
            k: v
            for k, v in item.items()
            if k not in {"type", "meters", "transformers", "startTick", "durationTicks", "repeatEveryTicks"}
        }
        specs.append(
            AnomalySpec(
                kind,
                count,
                int(item.get("startTick", 0)),
                max(1, int(item.get("durationTicks", 1))),
                int(item.get("repeatEveryTicks", 0)),
                params,
            )
        )
    return specs


class AnomalyPlan:
    """Decides, deterministically, which meters are affected at which tick, and records the ground truth."""

    def __init__(self, specs: list[AnomalySpec], meters: list[Meter], seed: int):
        self.specs = specs
        self.meters = meters
        self.seed = seed
        self._prosumers = [m for m in meters if m.prosumer]
        self._transformers = sorted({m.transformer for m in meters})
        self._occurrences: dict[tuple[int, int], Occurrence] = {}

    def occurrences_at(self, tick: int) -> list[Occurrence]:
        active = []
        for index, spec in enumerate(self.specs):
            if tick < spec.start_tick:
                continue
            cycle = 0
            if spec.repeat_every_ticks > 0:
                cycle = (tick - spec.start_tick) // spec.repeat_every_ticks
            first = spec.start_tick + cycle * max(spec.repeat_every_ticks, 0)
            if first <= tick < first + spec.duration_ticks:
                active.append(self._occurrence(index, cycle, spec, first))
        return active

    def _occurrence(self, index: int, cycle: int, spec: AnomalySpec, first: int) -> Occurrence:
        key = (index, cycle)
        if key not in self._occurrences:
            rng = random.Random(f"{self.seed}:{index}:{cycle}")
            meters: tuple[str, ...] = ()
            transformers: tuple[str, ...] = ()
            if spec.type in PER_TRANSFORMER:
                transformers = tuple(
                    sorted(rng.sample(self._transformers, min(spec.count, len(self._transformers))))
                )
                meters = tuple(m.id for m in self.meters if m.transformer in transformers)
            else:
                pool = self._prosumers if spec.type == "NIGHT_EXPORT" and self._prosumers else self.meters
                meters = tuple(sorted(m.id for m in rng.sample(pool, min(spec.count, len(pool)))))
            self._occurrences[key] = Occurrence(
                f"ANOM-{index + 1:02d}-{cycle + 1:04d}",
                spec.type,
                first,
                first + spec.duration_ticks - 1,
                meters,
                transformers,
                dict(spec.params),
            )
        return self._occurrences[key]

    def effects(self, meter: Meter, tick: int) -> list[Occurrence]:
        return [o for o in self.occurrences_at(tick) if meter.id in o.meter_ids]

    def ground_truth(self, period) -> dict:
        """period(tick) -> (start, end) of the simulated time covered by a tick."""
        meters = {m.id: m for m in self.meters}
        items = []
        for occurrence in sorted(self._occurrences.values(), key=lambda o: (o.first_tick, o.id)):
            start, _ = period(occurrence.first_tick)
            _, end = period(occurrence.last_tick)
            affected = [meters[i] for i in occurrence.meter_ids if i in meters]
            items.append(
                {
                    "id": occurrence.id,
                    "type": occurrence.type,
                    "from": iso(start),
                    "to": iso(end),
                    "firstSeq": occurrence.first_tick + 1,
                    "lastSeq": occurrence.last_tick + 1,
                    "meterIds": list(occurrence.meter_ids),
                    "transformerIds": list(occurrence.transformer_ids)
                    or sorted({m.transformer for m in affected}),
                    "stations": sorted({m.station for m in affected}),
                    "zones": sorted({m.zone for m in affected}),
                    "parameters": occurrence.params,
                }
            )
        return {"dataOrigin": "SIMULATED", "seed": self.seed, "anomalies": items}

    def write(self, path: Path, period, sim_start: datetime, interval_minutes: int) -> None:
        data = self.ground_truth(period)
        data["simStart"] = iso(sim_start)
        data["intervalMinutes"] = interval_minutes
        path.parent.mkdir(parents=True, exist_ok=True)
        tmp = path.with_suffix(".tmp")
        tmp.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
        tmp.replace(path)

    @property
    def known(self) -> int:
        return len(self._occurrences)


def iso(moment: datetime) -> str:
    return moment.isoformat().replace("+00:00", "Z")
