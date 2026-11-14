from datetime import UTC, datetime, timedelta

import pandas as pd
import pytest

from filegrid_intelligence.agent02 import GridIntelligence, evaluate
from filegrid_intelligence.detectors import detect_all, is_night

START = datetime(2026, 11, 14, 12, 0, tzinfo=UTC)  # daytime start; 48 ticks of 15 min = 12 h


def make_df(meters=6, posts=("PT-A", "PT-B", "PT-C"), ticks=48, mutate=None, skip=None) -> pd.DataFrame:
    rows = []
    for m in range(meters):
        meter = f"MTR-{m:03d}"
        post = posts[m % len(posts)]
        for t in range(ticks):
            if skip and skip(meter, t):
                continue
            start = START + timedelta(minutes=15 * t)
            row = {
                "reading_id": f"{meter}-{t}", "meter_id": meter, "seq": t + 1, "period_start": start,
                "period_end": start + timedelta(minutes=15), "region": "Muntenia", "zone": "București",
                "station": "ST-MUN-01", "transformer": post, "prosumer": m == 0, "n": 15,
                "kwh_import": 0.2 + 0.01 * ((t * 7 + m) % 5), "kwh_export": 0.0, "min_import": 0.01,
                "v_min": 225.0 + (m % 3), "v_avg": 229.0 + 0.1 * ((t + m) % 4), "v_max": 233.0, "n_distinct": 15,
            }
            if mutate:
                mutate(row, meter, post, t)
            rows.append(row)
    return GridIntelligence._normalise(pd.DataFrame(rows))


def types(detections):
    return sorted({d.type for d in detections})


def test_clean_data_has_no_findings():
    assert detect_all(make_df()) == []


def test_zero_consumption():
    def mutate(row, meter, post, t):
        if meter == "MTR-001" and 10 <= t < 20:
            row.update(kwh_import=0.0, kwh_export=0.0, min_import=0.0)

    dets = [d for d in detect_all(make_df(mutate=mutate)) if d.type == "ZERO_CONSUMPTION"]
    assert len(dets) == 1
    d = dets[0]
    assert (d.meter_id, d.first_seq, d.last_seq) == ("MTR-001", 11, 20)
    assert 0 < d.confidence <= 0.95
    assert "consum zero" in d.observations
    assert d.human_check


def test_spike():
    def mutate(row, meter, post, t):
        if meter == "MTR-002" and t in (30, 31):
            row["kwh_import"] *= 8

    dets = [d for d in detect_all(make_df(mutate=mutate)) if d.type == "SPIKE"]
    assert [(d.meter_id, d.first_seq, d.last_seq) for d in dets] == [("MTR-002", 31, 32)]
    assert dets[0].evidence["ratioToOwnMedian"] >= 4


def test_missing_readings():
    dets = detect_all(make_df(skip=lambda meter, t: meter == "MTR-003" and 5 <= t < 9))
    missing = [d for d in dets if d.type == "MISSING_READINGS"]
    assert [(d.meter_id, d.first_seq, d.last_seq) for d in missing] == [("MTR-003", 6, 9)]
    assert missing[0].evidence["missingCount"] == 4


def test_missing_at_newest_tick_is_not_reported_yet():
    dets = detect_all(make_df(skip=lambda meter, t: meter == "MTR-003" and t == 46))
    assert not [d for d in dets if d.type == "MISSING_READINGS"]


def test_repeated_values():
    def mutate(row, meter, post, t):
        if meter == "MTR-004" and 12 <= t < 18:
            row.update(n_distinct=1, kwh_import=0.25, v_avg=229.0)

    dets = [d for d in detect_all(make_df(mutate=mutate)) if d.type == "REPEATED_VALUES"]
    assert [(d.meter_id, d.first_seq, d.last_seq) for d in dets] == [("MTR-004", 13, 18)]


def test_impossible_values_take_precedence():
    def mutate(row, meter, post, t):
        if meter == "MTR-005" and t == 40:
            row.update(kwh_import=-20.0, min_import=-1.5, v_min=0.0, v_avg=0.0)

    dets = detect_all(make_df(mutate=mutate))
    assert types(dets) == ["IMPOSSIBLE_VALUES"]
    assert dets[0].confidence >= 0.95


def test_low_voltage_per_post():
    def mutate(row, meter, post, t):
        if post == "PT-B" and 20 <= t < 26:
            row["v_avg"] -= 28
            row["v_min"] -= 28

    dets = detect_all(make_df(meters=18, mutate=mutate))
    low = [d for d in dets if d.type == "LOW_VOLTAGE"]
    assert [(d.post, d.first_seq, d.last_seq) for d in low] == [("PT-B", 21, 26)]
    assert low[0].meter_id is None
    assert low[0].confidence >= 0.8
    assert "mediana rețelei" in low[0].observations


def test_night_export():
    def mutate(row, meter, post, t):
        if meter == "MTR-000" and is_night(row["period_start"]) and t in (40, 41, 42):
            row["kwh_export"] = 0.3

    dets = [d for d in detect_all(make_df(mutate=mutate)) if d.type == "NIGHT_EXPORT"]
    assert [(d.meter_id, d.first_seq, d.last_seq) for d in dets] == [("MTR-000", 41, 43)]


def test_texts_never_assert_causes():
    def mutate(row, meter, post, t):
        if meter == "MTR-001" and 10 <= t < 20:
            row.update(kwh_import=0.0, kwh_export=0.0)

    for d in detect_all(make_df(mutate=mutate)):
        text = (d.observations + " " + d.human_check).lower()
        assert "cauza este" not in text and "a fost cauzat" not in text
        assert "trebuie confirmat" in text or "verificați" in text


@pytest.mark.parametrize("hour,night", [(2, True), (13, False), (23, True)])
def test_is_night(hour, night):
    assert is_night(datetime(2026, 11, 14, hour, 0, tzinfo=UTC)) is night


def test_evaluation_precision_recall():
    def mutate(row, meter, post, t):
        if meter == "MTR-001" and 10 <= t < 20:
            row.update(kwh_import=0.0, kwh_export=0.0)

    dets = detect_all(make_df(mutate=mutate))
    truth = {"anomalies": [
        {"type": "ZERO_CONSUMPTION", "firstSeq": 11, "lastSeq": 20, "meterIds": ["MTR-001"], "transformerIds": []},
        {"type": "SPIKE", "firstSeq": 30, "lastSeq": 31, "meterIds": ["MTR-002"], "transformerIds": []},
        {"type": "SPIKE", "firstSeq": 100, "lastSeq": 101, "meterIds": ["MTR-002"], "transformerIds": []},
    ]}
    result = evaluate(dets, truth, 1, 48)
    assert result["perType"]["ZERO_CONSUMPTION"] == {
        "findings": 1, "truePositives": 1, "falsePositives": 0, "injected": 1, "detected": 1,
        "precision": 1.0, "recall": 1.0}
    assert result["perType"]["SPIKE"]["injected"] == 1  # the out-of-range one is ignored
    assert result["recall"] == 0.5
    assert result["precision"] == 1.0
