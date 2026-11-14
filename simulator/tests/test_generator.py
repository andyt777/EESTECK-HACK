import json
from datetime import UTC, datetime

from filegrid_simulator import profiles
from filegrid_simulator.anomalies import AnomalyPlan, AnomalySpec, load_specs
from filegrid_simulator.generator import ReadingGenerator
from filegrid_simulator.topology import build_meters, load_topology

from .conftest import ROOT

MONDAY = datetime(2026, 11, 16, tzinfo=UTC)
SATURDAY = datetime(2026, 11, 14, tzinfo=UTC)


def meters(count=120):
    return build_meters(load_topology(ROOT / "topology.yaml"), count, 0.10, seed=7)


def test_reading_is_a_small_json_file_with_the_contract_fields():
    generator = ReadingGenerator(AnomalyPlan([], meters(), 7), 15, 7)
    reading = generator.reading(meters()[0], 3, MONDAY)
    size = len(json.dumps(reading).encode())
    assert 1024 <= size <= 50 * 1024
    for key in (
        "meterId",
        "region",
        "zone",
        "station",
        "transformer",
        "periodStart",
        "periodEnd",
        "intervalMinutes",
        "values",
        "seq",
        "dataOrigin",
    ):
        assert key in reading
    assert reading["dataOrigin"] == "SIMULATED"
    assert reading["seq"] == 4
    assert reading["periodStart"] == "2026-11-16T00:00:00Z"
    assert reading["periodEnd"] == "2026-11-16T00:15:00Z"
    assert len(reading["values"]) == 15
    assert {"kwhImport", "kwhExport", "voltage", "current"} <= set(reading["values"][0])


def test_readings_are_deterministic():
    generator = ReadingGenerator(AnomalyPlan([], meters(), 7), 15, 7)
    assert generator.reading(meters()[5], 10, MONDAY) == generator.reading(meters()[5], 10, MONDAY)


def test_daily_curve_weekend_temperature_and_solar_profiles():
    night = MONDAY.replace(hour=3)
    evening = MONDAY.replace(hour=19, minute=30)
    assert profiles.daily_factor(evening) > 2 * profiles.daily_factor(night)
    assert profiles.daily_factor(MONDAY.replace(hour=7, minute=30)) != profiles.daily_factor(
        SATURDAY.replace(hour=7, minute=30)
    )
    assert profiles.temperature_factor(datetime(2026, 1, 15, 6, tzinfo=UTC)) > profiles.temperature_factor(
        datetime(2026, 5, 15, 15, tzinfo=UTC)
    )
    assert profiles.solar_fraction(MONDAY.replace(hour=2)) == 0
    assert profiles.solar_fraction(MONDAY.replace(hour=13)) > 0.2
    assert profiles.solar_fraction(datetime(2026, 6, 21, 13, tzinfo=UTC)) > profiles.solar_fraction(
        MONDAY.replace(hour=13)
    )


def test_prosumers_export_at_noon_and_not_at_night():
    prosumer = next(m for m in meters() if m.prosumer)
    generator = ReadingGenerator(AnomalyPlan([], meters(), 7), 15, 7)
    noon = generator.reading(prosumer, 1, datetime(2026, 6, 21, 12, tzinfo=UTC))
    night = generator.reading(prosumer, 2, datetime(2026, 6, 21, 1, tzinfo=UTC))
    assert sum(v["kwhExport"] for v in noon["values"]) > 0
    assert sum(v["kwhExport"] for v in night["values"]) == 0


def plan_with(spec: AnomalySpec, pool=None):
    pool = pool or meters()
    return AnomalyPlan([spec], pool, 7), pool


def test_zero_consumption_spike_and_missing_readings():
    zero_plan, pool = plan_with(AnomalySpec("ZERO_CONSUMPTION", 2, 5, 3))
    target = zero_plan.occurrences_at(5)[0].meter_ids[0]
    meter = next(m for m in pool if m.id == target)
    generator = ReadingGenerator(zero_plan, 15, 7)
    assert all(
        v["kwhImport"] == 0 and v["kwhExport"] == 0 for v in generator.reading(meter, 6, MONDAY)["values"]
    )
    assert sum(v["kwhImport"] for v in generator.reading(meter, 9, MONDAY)["values"]) > 0

    spike_plan, _ = plan_with(AnomalySpec("SPIKE", 1, 0, 1, params={"factor": 8}))
    spiked = next(m for m in pool if m.id in spike_plan.occurrences_at(0)[0].meter_ids)
    normal = ReadingGenerator(AnomalyPlan([], pool, 7), 15, 7).reading(spiked, 0, MONDAY.replace(hour=19))
    peak = ReadingGenerator(spike_plan, 15, 7).reading(spiked, 0, MONDAY.replace(hour=19))
    assert sum(v["kwhImport"] for v in peak["values"]) > 5 * sum(v["kwhImport"] for v in normal["values"])

    missing_plan, _ = plan_with(AnomalySpec("MISSING_READINGS", 1, 2, 2))
    silent = next(m for m in pool if m.id in missing_plan.occurrences_at(2)[0].meter_ids)
    generator = ReadingGenerator(missing_plan, 15, 7)
    assert generator.reading(silent, 2, MONDAY) is None
    assert generator.reading(silent, 3, MONDAY) is None
    assert generator.reading(silent, 4, MONDAY)["seq"] == 5  # the gap stays visible in seq


def test_repeated_values_low_voltage_night_export_and_impossible_values():
    pool = meters()
    repeat_plan = AnomalyPlan([AnomalySpec("REPEATED_VALUES", 1, 0, 5)], pool, 7)
    stuck = next(m for m in pool if m.id in repeat_plan.occurrences_at(0)[0].meter_ids)
    generator = ReadingGenerator(repeat_plan, 15, 7)
    first = generator.reading(stuck, 1, MONDAY.replace(hour=8))["values"]
    later = generator.reading(stuck, 3, MONDAY.replace(hour=8, minute=30))["values"]
    strip = [{k: v for k, v in s.items() if k != "minute"} for s in first + later]
    assert all(s == strip[0] for s in strip)

    low_plan = AnomalyPlan([AnomalySpec("LOW_VOLTAGE", 1, 0, 1, params={"voltageDrop": 28})], pool, 7)
    post = low_plan.occurrences_at(0)[0].transformer_ids[0]
    on_post = [m for m in pool if m.transformer == post]
    assert len(on_post) > 1
    low = ReadingGenerator(low_plan, 15, 7)
    assert all(max(v["voltage"] for v in low.reading(m, 0, MONDAY)["values"]) < 210 for m in on_post)

    export_plan = AnomalyPlan([AnomalySpec("NIGHT_EXPORT", 1, 0, 1)], pool, 7)
    exporter = next(m for m in pool if m.id in export_plan.occurrences_at(0)[0].meter_ids)
    assert exporter.prosumer
    night = ReadingGenerator(export_plan, 15, 7).reading(exporter, 0, MONDAY.replace(hour=2))
    assert sum(v["kwhExport"] for v in night["values"]) > 0

    bad_plan = AnomalyPlan([AnomalySpec("IMPOSSIBLE_VALUES", 1, 0, 1)], pool, 7)
    bad = next(m for m in pool if m.id in bad_plan.occurrences_at(0)[0].meter_ids)
    values = ReadingGenerator(bad_plan, 15, 7).reading(bad, 0, MONDAY)["values"]
    assert all(v["kwhImport"] < 0 and v["voltage"] == 0 for v in values)


def test_anomalies_repeat_on_other_meters_and_ground_truth_is_written(settings, tmp_path):
    pool = meters()
    plan = AnomalyPlan(load_specs(ROOT / "anomalies.yaml"), pool, 7)
    for tick in range(0, 200):
        plan.occurrences_at(tick)
    path = tmp_path / "injected_anomalies.json"
    plan.write(path, settings.period, settings.sim_start, 15)
    truth = json.loads(path.read_text(encoding="utf-8"))
    assert truth["dataOrigin"] == "SIMULATED"
    kinds = {a["type"] for a in truth["anomalies"]}
    assert kinds == {
        "ZERO_CONSUMPTION",
        "SPIKE",
        "MISSING_READINGS",
        "REPEATED_VALUES",
        "LOW_VOLTAGE",
        "NIGHT_EXPORT",
        "IMPOSSIBLE_VALUES",
    }
    zero = [a for a in truth["anomalies"] if a["type"] == "ZERO_CONSUMPTION"]
    assert len(zero) == 2  # occurrences starting at ticks 12 and 108 (the next one, 204, is past tick 199)
    assert len({tuple(a["meterIds"]) for a in zero}) == 2  # each occurrence hits other meters
    assert zero[0]["from"] == "2026-11-10T03:00:00Z"
    assert zero[0]["firstSeq"] == 13
    assert all(a["meterIds"] and a["transformerIds"] and a["stations"] for a in truth["anomalies"])
