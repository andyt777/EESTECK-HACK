from pathlib import Path

import pytest

from filegrid_intelligence.topology import Topology
from filegrid_intelligence.voice import SimulatedVoiceProvider, build_script, parse_field_report

TOPOLOGY = Topology(
    regions=[{"id": "DOB", "name": "Dobrogea"}],
    stations=[{"id": "ST-DOB-04", "name": "Tulcea", "region": "DOB", "zone": "Tulcea"}],
    posts=[{"id": "PT-DOB-04-03", "station": "ST-DOB-04"}],
)


@pytest.mark.parametrize("text,kind,severity", [
    ("Avem un incendiu la postul de transformare, iese fum", "FIRE_REPORTED", "CRITICAL"),
    ("Un cablu căzut pe stradă, pericol pentru trecători", "FIELD_EMERGENCY", "CRITICAL"),
    ("Cartierul e fără curent de o oră, întrerupere totală", "GRID_FAULT_MAJOR", "HIGH"),
    ("Transformatorul face zgomot", "GRID_FAULT_MAJOR", "MEDIUM"),
    ("Clienții spun că lumina pâlpâie, tensiunea pare mică", "GRID_ANOMALY", "LOW"),
])
def test_field_report_classification(text, kind, severity):
    p = parse_field_report(text, topology=TOPOLOGY)
    assert (p["type"], p["severity"]) == (kind, severity)
    assert p["certainty"] == "REPORTED" and p["source"] == "USER"
    assert text in p["description"]


def test_field_report_extracts_post_and_station():
    p = parse_field_report("Fără tensiune la PT-DOB-04-03", topology=TOPOLOGY)
    assert p["asset"] == "PT-DOB-04-03"
    assert p["station"] == "ST-DOB-04"
    assert p["zone"] == "Tulcea"


def test_field_report_station_by_name():
    p = parse_field_report("Avarie în stația Tulcea", topology=TOPOLOGY)
    assert (p["station"], p["zone"]) == ("ST-DOB-04", "Tulcea")


def test_explicit_zone_wins():
    p = parse_field_report("avarie", zone="Constanța", station="ST-DOB-01", topology=TOPOLOGY)
    assert (p["zone"], p["station"]) == ("Constanța", "ST-DOB-01")


def test_script_and_simulated_call():
    incident = {"code": "INC-0007", "severity": "HIGH", "certainty": "SUSPECTED", "title": "Tensiune scăzută",
                "zone": "Tulcea", "station": "ST-DOB-04", "asset": "PT-DOB-04-03"}
    script = build_script(incident, "id", "FIELD_TEAM", "Aduceți analizorul de rețea")
    assert "INC-0007" in script and "suspectat" in script and "analizorul" in script
    status, transcript = SimulatedVoiceProvider().place_call("FIELD_TEAM", script)
    assert status == "COMPLETED" and transcript.startswith("[SIMULAT]")


def test_real_topology_file_loads():
    from filegrid_intelligence.topology import load_topology

    topo = load_topology(Path("/nonexistent.yaml"))
    assert topo.posts == []
