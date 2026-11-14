from filegrid_simulator.config import load
from filegrid_simulator.topology import build_meters, load_topology

from .conftest import ROOT


def test_topology_has_three_regions_twelve_stations_sixty_posts_and_valid_lines():
    topology = load_topology(ROOT / "topology.yaml")
    raw = topology.raw
    assert {r["name"] for r in raw["regions"]} == {"Muntenia", "Banat", "Dobrogea"}
    assert len(raw["stations"]) == 12
    assert len(topology.transformers) == 60
    ids = {s["id"] for s in raw["stations"]} | {t.id for t in topology.transformers}
    assert all(line["from"] in ids and line["to"] in ids for line in raw["lines"])
    assert all(0 <= n["x"] <= 1000 and 0 <= n["y"] <= 600 for n in raw["stations"] + raw["transformers"])
    assert raw["dataOrigin"] == "SIMULATED"


def test_meters_are_deterministic_spread_over_every_post_with_about_ten_percent_prosumers():
    topology = load_topology(ROOT / "topology.yaml")
    first = build_meters(topology, 2000, 0.10, seed=42)
    again = build_meters(topology, 2000, 0.10, seed=42)
    assert first == again
    assert len({m.id for m in first}) == 2000
    assert {m.transformer for m in first} == {t.id for t in topology.transformers}
    share = sum(m.prosumer for m in first) / len(first)
    assert 0.07 <= share <= 0.13
    assert all(m.pv_kwp > 0 for m in first if m.prosumer)


def test_peak_mode_is_ten_times_the_normal_rate():
    normal = load(["--mode", "normal"], env={"METERS": "2000", "INTERVAL_SECONDS": "60"})
    peak = load(["--mode", "peak"], env={"METERS": "2000", "INTERVAL_SECONDS": "60"})
    assert normal.readings_per_minute == 2000
    assert peak.rate_multiplier == 10
    assert peak.readings_per_minute == 10 * normal.readings_per_minute


def test_environment_settings_and_explicit_rate_win():
    settings = load(
        [],
        env={
            "MODE": "peak",
            "RATE_MULTIPLIER": "3",
            "METERS": "10",
            "TIME_SCALE": "2",
            "DURATION": "5",
            "API_URL": "http://api:8080/api/",
        },
    )
    assert settings.rate_multiplier == 3
    assert settings.time_scale == 2
    assert settings.duration_seconds == 5
    assert settings.api_url == "http://api:8080/api"
    assert settings.readings_per_minute == 10 * 60 / 60 * 3 * 2
