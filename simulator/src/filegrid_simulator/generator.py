"""Builds the readings: one small JSON document per meter and period."""

from __future__ import annotations

import math
import random
from datetime import datetime, timedelta

from . import profiles
from .anomalies import AnomalyPlan, Occurrence, iso
from .topology import Meter

NOMINAL_VOLTAGE = 230.0


class ReadingGenerator:
    def __init__(self, plan: AnomalyPlan, interval_minutes: int, seed: int):
        self.plan = plan
        self.interval_minutes = interval_minutes
        self.seed = seed

    def reading(self, meter: Meter, tick: int, period_start: datetime) -> dict | None:
        """The reading of ``meter`` for ``tick``, or None when the meter stays silent (missing reading)."""
        effects = self.plan.effects(meter, tick)
        kinds = {o.type for o in effects}
        if "MISSING_READINGS" in kinds:
            return None
        period_end = period_start + timedelta(minutes=self.interval_minutes)
        frozen = next((o for o in effects if o.type == "REPEATED_VALUES"), None)
        if frozen is not None:
            # The meter is stuck: every sample of every period repeats the first sample of the occurrence.
            first_start = period_start - timedelta(minutes=self.interval_minutes * (tick - frozen.first_tick))
            values = self._samples(meter, frozen.first_tick, first_start, effects=[], repeat_minute=0)
        else:
            values = self._samples(meter, tick, period_start, effects)
        return {
            "meterId": meter.id,
            "region": meter.region_name,
            "zone": meter.zone,
            "station": meter.station,
            "transformer": meter.transformer,
            "prosumer": meter.prosumer,
            "periodStart": iso(period_start),
            "periodEnd": iso(period_end),
            "intervalMinutes": self.interval_minutes,
            "seq": tick + 1,
            "units": {"energy": "kWh", "voltage": "V", "current": "A"},
            "values": values,
            "dataOrigin": "SIMULATED",
        }

    def _samples(
        self,
        meter: Meter,
        tick: int,
        period_start: datetime,
        effects: list[Occurrence],
        repeat_minute: int | None = None,
    ) -> list[dict]:
        rng = random.Random(f"{self.seed}:{meter.id}:{tick}")
        kinds = {o.type: o for o in effects}
        cloud = 0.6 + 0.4 * rng.random()
        samples = []
        frozen_sample = None
        for minute in range(self.interval_minutes):
            moment = period_start + timedelta(minutes=minute)
            if repeat_minute is not None and frozen_sample is not None:
                samples.append(dict(frozen_sample, minute=minute))
                continue
            demand = profiles.demand_kw(meter.base_kw, moment) * math.exp(rng.gauss(0, 0.08))
            pv = meter.pv_kwp * profiles.solar_fraction(moment) * cloud if meter.prosumer else 0.0
            if "ZERO_CONSUMPTION" in kinds:
                demand, pv = 0.0, 0.0
            if "SPIKE" in kinds:
                demand *= float(kinds["SPIKE"].params.get("factor", 8))
            if "NIGHT_EXPORT" in kinds:
                pv = max(pv, demand + meter.pv_kwp * 0.3)
            import_kw = max(0.0, demand - pv)
            export_kw = max(0.0, pv - demand)
            voltage = NOMINAL_VOLTAGE + rng.gauss(0, 1.2) - 4.0 * min(1.5, demand) + 2.5 * min(2.0, export_kw)
            if "LOW_VOLTAGE" in kinds:
                voltage -= float(kinds["LOW_VOLTAGE"].params.get("voltageDrop", 28))
            sample = {
                "minute": minute,
                "kwhImport": round(import_kw / 60.0, 5),
                "kwhExport": round(export_kw / 60.0, 5),
                "voltage": round(voltage, 1),
                "current": round((import_kw + export_kw) * 1000.0 / max(voltage, 1.0), 3),
            }
            if "IMPOSSIBLE_VALUES" in kinds:
                sample.update(kwhImport=-round(1 + rng.random(), 3), voltage=0.0, current=0.0)
            if repeat_minute is not None and minute == repeat_minute:
                frozen_sample = sample
            samples.append(sample)
        return samples
