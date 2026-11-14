"""Agent 02 detectors: deterministic rules + robust statistics over per-reading features.

Input: a DataFrame with one row per meter reading (see FEATURE_COLUMNS). Output: a list of
Detection objects. Detectors never state causes: the texts describe what was observed, the
evidence, a confidence score and the human check needed (docs/ARCHITECTURE.md §A.3.11).
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field
from datetime import datetime

import numpy as np
import pandas as pd

from . import texts

FEATURE_COLUMNS = [
    "reading_id", "meter_id", "seq", "period_start", "period_end", "region", "zone", "station",
    "transformer", "prosumer", "n", "kwh_import", "kwh_export", "min_import", "v_min", "v_avg", "v_max",
    "n_distinct",
]
TYPES = (
    "ZERO_CONSUMPTION", "SPIKE", "MISSING_READINGS", "REPEATED_VALUES", "LOW_VOLTAGE", "NIGHT_EXPORT",
    "IMPOSSIBLE_VALUES",
)

SPIKE_RATIO = 4.0
SPIKE_MIN_READINGS = 8
LOW_VOLTAGE_DROP_V = 15.0
NIGHT_EXPORT_MIN_KWH = 0.001
EVIDENCE_POINTS = 12


@dataclass
class Detection:
    type: str
    title: str
    observations: str
    human_check: str
    confidence: float
    confidence_explanation: str
    evidence: dict
    first_seq: int
    last_seq: int
    window_start: datetime | None
    window_end: datetime | None
    meter_id: str | None = None
    post: str | None = None
    station: str | None = None
    zone: str | None = None
    region: str | None = None
    extra: dict = field(default_factory=dict)

    @property
    def fingerprint(self) -> str:
        subject = self.meter_id or self.post or "grid"
        return f"{self.type}:{subject}:{self.first_seq}"


def is_night(moment: datetime) -> bool:
    """Same clear-sky model as the simulator: True when the PV production is 0 (UTC hours)."""
    day_of_year = moment.timetuple().tm_yday
    day_length = 12.0 + 3.5 * math.sin(2 * math.pi * (day_of_year - 80) / 365.0)
    sunrise, sunset = 13.0 - day_length / 2, 13.0 + day_length / 2
    hour = moment.hour + moment.minute / 60.0
    # One hour of margin around dawn/dusk keeps twilight production out of the rule.
    return hour <= sunrise - 1.0 or hour >= sunset + 1.0


def _clip(value: float, low: float = 0.05, high: float = 0.99) -> float:
    return round(float(min(high, max(low, value))), 2)


def _ts(value) -> datetime | None:
    if value is None or (isinstance(value, float) and math.isnan(value)):
        return None
    return pd.Timestamp(value).to_pydatetime()


def _series(rows: pd.DataFrame) -> list[dict]:
    rows = rows.head(EVIDENCE_POINTS)
    return [
        {
            "seq": int(r.seq),
            "periodStart": pd.Timestamp(r.period_start).isoformat(),
            "kwhImport": round(float(r.kwh_import), 4),
            "kwhExport": round(float(r.kwh_export), 4),
            "voltageAvg": round(float(r.v_avg), 1),
            "voltageMin": round(float(r.v_min), 1),
            "readingId": r.reading_id,
        }
        for r in rows.itertuples()
    ]


def _runs(flagged: pd.DataFrame):
    """Yields (meter_id, rows) for every run of consecutive seq numbers per meter."""
    if flagged.empty:
        return
    flagged = flagged.sort_values(["meter_id", "seq"])
    group = flagged["seq"] - flagged.groupby("meter_id", observed=True).cumcount()
    for (meter_id, _), rows in flagged.groupby([flagged["meter_id"], group], observed=True, sort=False):
        yield meter_id, rows


def _place(row) -> dict:
    return {
        "meter_id": row.meter_id,
        "post": row.transformer,
        "station": row.station,
        "zone": row.zone,
        "region": row.region,
    }


def _impossible_mask(df: pd.DataFrame) -> pd.Series:
    return (df["min_import"] < 0) | (df["kwh_export"] < 0) | (df["v_min"] <= 0) | (df["v_max"] > 300)


def detect_impossible(df: pd.DataFrame) -> list[Detection]:
    out = []
    for meter_id, rows in _runs(df[_impossible_mask(df)]):
        first = rows.iloc[0]
        n = len(rows)
        obs = texts.impossible(meter_id, first.transformer, first.station, n, int(rows.seq.min()),
                               int(rows.seq.max()), float(rows.min_import.min()), float(rows.v_min.min()))
        out.append(_meter_detection("IMPOSSIBLE_VALUES", rows, obs, 0.97,
                                    "Regulă fizică: energie negativă sau tensiune 0 V / peste 300 V nu pot fi măsurate "
                                    "de un contor funcțional; scor fix 0,97.",
                                    {"minImportKwh": float(rows.min_import.min()), "minVoltage": float(rows.v_min.min())}))
    return out


def detect_zero(df: pd.DataFrame) -> list[Detection]:
    ok = df[~_impossible_mask(df)]
    meter_median = ok.groupby("meter_id", observed=True)["kwh_import"].median()
    flagged = ok[(ok["kwh_import"] + ok["kwh_export"]).abs() <= 1e-9]
    out = []
    for meter_id, rows in _runs(flagged):
        median = float(meter_median.get(meter_id, 0.0))
        if len(rows) < 2 or median <= 0:
            continue
        first = rows.iloc[0]
        n = len(rows)
        conf = _clip(0.55 + 0.05 * n, high=0.95)
        obs = texts.zero(meter_id, first.transformer, first.station, n, int(rows.seq.min()), int(rows.seq.max()),
                         median)
        out.append(_meter_detection("ZERO_CONSUMPTION", rows, obs, conf,
                                    f"0,55 + 0,05 × {n} intervale consecutive cu import și export 0 (plafon 0,95).",
                                    {"meterMedianKwh": round(median, 4)}))
    return out


def detect_spike(df: pd.DataFrame) -> list[Detection]:
    ok = df[~_impossible_mask(df)].copy()
    if ok.empty:
        return []
    population = ok.groupby("period_start")["kwh_import"].transform("median").replace(0, np.nan)
    ok["norm"] = ok["kwh_import"] / population
    counts = ok.groupby("meter_id", observed=True)["norm"].transform("count")
    base = ok.groupby("meter_id", observed=True)["norm"].transform("median")
    mad = ok.groupby("meter_id", observed=True)["norm"].transform(lambda s: (s - s.median()).abs().median())
    ok["ratio"] = ok["norm"] / base.replace(0, np.nan)
    ok["z"] = (ok["norm"] - base) / (1.4826 * mad.replace(0, np.nan))
    flagged = ok[(counts >= SPIKE_MIN_READINGS) & (ok["ratio"] >= SPIKE_RATIO) & (ok["kwh_import"] > 0)]
    out = []
    for meter_id, rows in _runs(flagged):
        first = rows.iloc[0]
        ratio = float(rows.ratio.max())
        z = float(np.nan_to_num(rows.z.max(), nan=99.0))
        conf = _clip(0.5 + ratio / 20.0, high=0.95)
        obs = texts.spike(meter_id, first.transformer, first.station, len(rows), int(rows.seq.min()),
                          int(rows.seq.max()), ratio, z)
        out.append(_meter_detection("SPIKE", rows, obs, conf,
                                    f"0,5 + raport/20, unde raport = {ratio:.1f} (consum normalizat la mediana populației "
                                    "în același interval, față de mediana proprie a contorului); plafon 0,95.",
                                    {"ratioToOwnMedian": round(ratio, 2), "robustZ": round(min(z, 99.0), 1)}))
    return out


def detect_repeated(df: pd.DataFrame) -> list[Detection]:
    flagged = df[(df["n"] > 1) & (df["n_distinct"] <= 1) & ~_impossible_mask(df)]
    out = []
    for meter_id, rows in _runs(flagged):
        first = rows.iloc[0]
        n = len(rows)
        conf = _clip(0.75 + 0.02 * n, high=0.95)
        obs = texts.repeated(meter_id, first.transformer, first.station, n, int(rows.seq.min()), int(rows.seq.max()),
                             int(first.n))
        out.append(_meter_detection("REPEATED_VALUES", rows, obs, conf,
                                    f"0,75 + 0,02 × {n} citiri în care toate eșantioanele (import, export, tensiune, "
                                    "curent) sunt identice; plafon 0,95.", {"samplesPerReading": int(first.n)}))
    return out


def detect_night_export(df: pd.DataFrame) -> list[Detection]:
    moments = {t: is_night(pd.Timestamp(t).to_pydatetime()) for t in df["period_start"].unique()}
    night = df["period_start"].map(moments).astype(bool)
    flagged = df[night & (df["kwh_export"] > NIGHT_EXPORT_MIN_KWH) & ~_impossible_mask(df)]
    out = []
    for meter_id, rows in _runs(flagged):
        first = rows.iloc[0]
        n = len(rows)
        total = float(rows.kwh_export.sum())
        conf = _clip(0.7 + 0.03 * n, high=0.93)
        obs = texts.night_export(meter_id, first.transformer, first.station, n, int(rows.seq.min()),
                                 int(rows.seq.max()), total, bool(first.prosumer))
        out.append(_meter_detection("NIGHT_EXPORT", rows, obs, conf,
                                    f"0,7 + 0,03 × {n} intervale de noapte (producție fotovoltaică teoretică 0) cu "
                                    "export > 0; plafon 0,93.", {"exportKwh": round(total, 4)}))
    return out


def detect_missing(df: pd.DataFrame) -> list[Detection]:
    if df.empty:
        return []
    # Readings of the newest tick may still be in flight: only gaps closed before it count.
    cutoff = int(df["seq"].max()) - 1
    ordered = df.sort_values(["meter_id", "seq"])
    grouped = ordered.groupby("meter_id", observed=True)
    ordered = ordered.assign(prev_seq=grouped["seq"].shift(1), prev_end=grouped["period_end"].shift(1),
                             prev_id=grouped["reading_id"].shift(1))
    gaps = ordered[(ordered["seq"] - ordered["prev_seq"] > 1) & (ordered["seq"] <= cutoff)]
    out = []
    for row in gaps.itertuples():
        first_missing, last_missing = int(row.prev_seq) + 1, int(row.seq) - 1
        n = last_missing - first_missing + 1
        start = _ts(row.prev_end)
        conf = _clip(0.6 + 0.06 * n, high=0.92)
        obs = texts.missing(row.meter_id, row.transformer, row.station, n, first_missing, last_missing)
        out.append(Detection(
            type="MISSING_READINGS", title=texts.TITLES["MISSING_READINGS"].format(subject=row.meter_id),
            observations=obs, human_check=texts.HUMAN_CHECK["MISSING_READINGS"], confidence=conf,
            confidence_explanation=f"0,6 + 0,06 × {n} citiri lipsă (secvența seq are un gol); plafon 0,92.",
            evidence={"missingSeqFrom": first_missing, "missingSeqTo": last_missing, "missingCount": n,
                      "lastReadingBefore": row.prev_id,
                      "firstReadingAfter": row.reading_id},
            first_seq=first_missing, last_seq=last_missing, window_start=start, window_end=_ts(row.period_start),
            **_place(row),
        ))
    return out


def detect_low_voltage(df: pd.DataFrame) -> list[Detection]:
    ok = df[~_impossible_mask(df)]
    if ok.empty:
        return []
    per_post = (
        ok.groupby(["transformer", "period_start"], observed=True)
        .agg(v=("v_avg", "mean"), meters=("meter_id", "nunique"), seq=("seq", "max"),
             station=("station", "first"), zone=("zone", "first"), region=("region", "first"))
        .reset_index()
    )
    per_post["median"] = per_post.groupby("period_start")["v"].transform("median")
    per_post["drop"] = per_post["median"] - per_post["v"]
    flagged = per_post[(per_post["drop"] >= LOW_VOLTAGE_DROP_V) & (per_post["meters"] >= 3)]
    out = []
    if flagged.empty:
        return out
    flagged = flagged.sort_values(["transformer", "seq"])
    group = flagged["seq"] - flagged.groupby("transformer", observed=True).cumcount()
    for (post, _), rows in flagged.groupby([flagged["transformer"], group], observed=True, sort=False):
        first = rows.iloc[0]
        n = len(rows)
        drop = float(rows["drop"].mean())
        conf = _clip(0.55 + drop / 80.0 + 0.04 * n, high=0.97)
        obs = texts.low_voltage(post, first.station, n, int(rows.seq.min()), int(rows.seq.max()),
                                float(rows.v.mean()), float(rows["median"].mean()), int(rows.meters.max()))
        out.append(Detection(
            type="LOW_VOLTAGE", title=texts.TITLES["LOW_VOLTAGE"].format(subject=post),
            observations=obs, human_check=texts.HUMAN_CHECK["LOW_VOLTAGE"], confidence=conf,
            confidence_explanation=f"0,55 + scădere medie ({drop:.1f} V)/80 + 0,04 × {n} intervale; plafon 0,97. "
                                   "Scăderea este față de mediana tensiunii tuturor posturilor în același interval.",
            evidence={"series": [{"seq": int(r.seq), "periodStart": pd.Timestamp(r.period_start).isoformat(),
                                  "postVoltageAvg": round(float(r.v), 1), "gridMedianVoltage": round(float(r.median), 1),
                                  "meters": int(r.meters)} for r in rows.head(EVIDENCE_POINTS).itertuples()],
                      "averageDropV": round(drop, 1), "metersAffected": int(rows.meters.max())},
            first_seq=int(rows.seq.min()), last_seq=int(rows.seq.max()),
            window_start=_ts(rows.period_start.min()), window_end=_ts(rows.period_start.max()),
            post=post, station=first.station, zone=first.zone, region=first.region,
            extra={"intervals": n},
        ))
    return out


def _meter_detection(kind: str, rows: pd.DataFrame, obs: str, conf: float, explanation: str, stats: dict) -> Detection:
    first = rows.iloc[0]
    return Detection(
        type=kind, title=texts.TITLES[kind].format(subject=first.meter_id), observations=obs,
        human_check=texts.HUMAN_CHECK[kind], confidence=conf, confidence_explanation=explanation,
        evidence={"series": _series(rows), "readingIds": list(rows.reading_id.head(EVIDENCE_POINTS)),
                  "intervals": len(rows), **stats},
        first_seq=int(rows.seq.min()), last_seq=int(rows.seq.max()),
        window_start=_ts(rows.period_start.min()), window_end=_ts(rows.period_end.max()),
        **_place(first),
    )


DETECTORS = (detect_impossible, detect_zero, detect_spike, detect_missing, detect_repeated, detect_low_voltage,
             detect_night_export)


def detect_all(df: pd.DataFrame) -> list[Detection]:
    if df.empty:
        return []
    out: list[Detection] = []
    for detector in DETECTORS:
        out.extend(detector(df))
    return out
