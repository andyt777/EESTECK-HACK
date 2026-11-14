"""Agent 02 - Grid Intelligence: loads readings from store-db, runs the detectors, persists findings
in meta-db, opens incidents for strong clustered findings and evaluates itself against the simulator
ground truth. Deterministic: no LLM is involved here."""

from __future__ import annotations

import json
import logging
import threading
import time
import uuid
from datetime import UTC, datetime, timedelta
from pathlib import Path

import pandas as pd
import psycopg
from psycopg.types.json import Jsonb

from . import texts
from .api_client import FileGridApi
from .db import Database
from .detectors import TYPES, Detection, detect_all
from .topology import Topology

log = logging.getLogger(__name__)

FEATURES_SQL = """
SELECT r.reading_id, r.meter_id, r.seq, r.period_start, r.period_end, r.region, r.zone, r.station,
       r.transformer, r.created_at,
       COALESCE((c.j->>'prosumer')::boolean, false) AS prosumer,
       a.n, a.kwh_import, a.kwh_export, a.min_import, a.v_min, a.v_avg, a.v_max, a.n_distinct
FROM meter_reading_blob r
CROSS JOIN LATERAL (SELECT convert_from(r.content, 'UTF8')::jsonb AS j) c
CROSS JOIN LATERAL (
    SELECT count(*) AS n,
           COALESCE(sum((v->>'kwhImport')::float8), 0) AS kwh_import,
           COALESCE(sum((v->>'kwhExport')::float8), 0) AS kwh_export,
           COALESCE(min((v->>'kwhImport')::float8), 0) AS min_import,
           COALESCE(min((v->>'voltage')::float8), 0) AS v_min,
           COALESCE(avg((v->>'voltage')::float8), 0) AS v_avg,
           COALESCE(max((v->>'voltage')::float8), 0) AS v_max,
           count(DISTINCT (v - 'minute')) AS n_distinct
    FROM jsonb_array_elements(c.j->'values') v
) a
WHERE (r.created_at, r.reading_id) > (%(after_ts)s, %(after_id)s)
  AND r.period_start >= %(min_period)s
  AND NOT (r.reading_id = ANY(%(excluded)s::varchar[]))
ORDER BY r.created_at, r.reading_id
LIMIT %(limit)s
"""
# Same ordering and filters as FEATURES_SQL, without decoding the content: locates a corrupt row cheaply.
READING_ID_AT_SQL = """
SELECT r.reading_id
FROM meter_reading_blob r
WHERE (r.created_at, r.reading_id) > (%(after_ts)s, %(after_id)s)
  AND r.period_start >= %(min_period)s
  AND NOT (r.reading_id = ANY(%(excluded)s::varchar[]))
ORDER BY r.created_at, r.reading_id
OFFSET %(offset)s
LIMIT 1
"""
PAGE = 20000
MAX_SKIPPED_PER_PAGE = 50
CATEGORICAL = ("meter_id", "region", "zone", "station", "transformer")
FINDING_COLUMNS = (
    "id, fingerprint, type, title, meter_id, post, station, zone, region, window_start, window_end, first_seq, "
    "last_seq, observations, evidence, confidence, confidence_explanation, human_verification_required, "
    "data_origin, status, status_note, incident_id, created_at, updated_at"
)


def _iso(value) -> str | None:
    if value is None:
        return None
    if isinstance(value, datetime):
        return value.astimezone(UTC).isoformat().replace("+00:00", "Z")
    return str(value)


def finding_json(row: dict, topology: Topology | None = None) -> dict:
    region = row.get("region")
    return {
        "id": str(row["id"]),
        "type": row["type"],
        "typeLabel": texts.TYPE_LABELS.get(row["type"], row["type"]),
        "title": row["title"],
        "meterId": row.get("meter_id"),
        "post": row.get("post"),
        "station": row.get("station"),
        "zone": row.get("zone"),
        "region": region,
        "regionName": topology.region_name(region) if topology else region,
        "window": {"from": _iso(row.get("window_start")), "to": _iso(row.get("window_end")),
                   "firstSeq": row.get("first_seq"), "lastSeq": row.get("last_seq")},
        "observations": row["observations"],
        "evidence": row.get("evidence") or {},
        "confidence": round(float(row["confidence"]), 2),
        "confidenceExplanation": row.get("confidence_explanation"),
        "humanVerificationRequired": row["human_verification_required"],
        "dataOrigin": row.get("data_origin", "SIMULATED"),
        "status": row["status"],
        "statusNote": row.get("status_note"),
        "incidentId": row.get("incident_id"),
        "createdAt": _iso(row.get("created_at")),
        "updatedAt": _iso(row.get("updated_at")),
    }


def evaluate(detections: list[Detection], ground_truth: dict, min_seq: int, max_seq: int) -> dict:
    """Precision/recall of the detections against the simulator's injected anomalies.

    Only occurrences fully inside the analysed seq range [min_seq, max_seq - 1] count. A ground-truth
    unit is (occurrence, meter) for meter anomalies and (occurrence, post) for LOW_VOLTAGE.
    """
    upper = max_seq - 1
    # The simulator records ground truth only for ticks it generated in its current process: after a
    # restart, earlier occurrences are missing from the file, so the evaluation starts where it is complete.
    recorded = [occ["firstSeq"] for occ in ground_truth.get("anomalies", []) if "firstSeq" in occ]
    coverage_from = int(ground_truth.get("coverageFromSeq") or (min(recorded) if recorded else min_seq))
    min_seq = max(min_seq, coverage_from)
    units: dict[str, list[tuple[str, int, int]]] = {t: [] for t in TYPES}
    for occ in ground_truth.get("anomalies", []):
        kind = occ.get("type")
        if kind not in units or occ["firstSeq"] < min_seq or occ["lastSeq"] > upper:
            continue
        subjects = occ.get("transformerIds", []) if kind == "LOW_VOLTAGE" else occ.get("meterIds", [])
        units[kind].extend((s, occ["firstSeq"], occ["lastSeq"]) for s in subjects)

    def overlaps(det: Detection, unit) -> bool:
        subject = det.post if det.type == "LOW_VOLTAGE" else det.meter_id
        return subject == unit[0] and det.first_seq <= unit[2] and det.last_seq >= unit[1]

    per_type = {}
    tp_total = fp_total = found_total = unit_total = 0
    for kind in TYPES:
        dets = [d for d in detections if d.type == kind and d.first_seq >= min_seq and d.last_seq <= upper]
        tp = sum(1 for d in dets if any(overlaps(d, u) for u in units[kind]))
        found = sum(1 for u in units[kind] if any(overlaps(d, u) for d in dets))
        fp = len(dets) - tp
        per_type[kind] = {
            "findings": len(dets), "truePositives": tp, "falsePositives": fp,
            "injected": len(units[kind]), "detected": found,
            "precision": round(tp / len(dets), 3) if dets else None,
            "recall": round(found / len(units[kind]), 3) if units[kind] else None,
        }
        tp_total, fp_total = tp_total + tp, fp_total + fp
        found_total, unit_total = found_total + found, unit_total + len(units[kind])
    return {
        "available": True,
        "seqRange": [min_seq, upper],
        "note": "Evaluarea acoperă doar intervalele (seq) pentru care fișierul de adevăr al simulatorului este complet "
                "și care au fost deja analizate.",
        "precision": round(tp_total / (tp_total + fp_total), 3) if tp_total + fp_total else None,
        "recall": round(found_total / unit_total, 3) if unit_total else None,
        "perType": per_type,
        "dataOrigin": "SIMULATED",
    }


class GridIntelligence:
    def __init__(self, settings, db: Database, topology: Topology, api: FileGridApi):
        self.settings = settings
        self.db = db
        self.topology = topology
        self.api = api
        self.df = pd.DataFrame()
        self.cursor = (datetime(1970, 1, 1, tzinfo=UTC), "")
        self.detections: list[Detection] = []
        self.last_run: dict | None = None
        self.lock = threading.Lock()
        # Readings whose stored bytes are not valid UTF-8 JSON (e.g. altered directly in storage): skipped.
        self.excluded: set[str] = set()

    # ------------------------------------------------------------------ loading
    def _features(self, params: dict, limit: int) -> list[dict]:
        with self.db.store.connection(timeout=10) as conn:
            return conn.execute(FEATURES_SQL, {**params, "excluded": sorted(self.excluded),
                                               "limit": limit}).fetchall()

    def _fetch_page(self, params: dict) -> list[dict]:
        """One page of features. A single undecodable reading makes the whole query fail, so on a data
        error the first corrupt row is located by bisection, excluded, and the page is fetched again."""
        skipped = 0
        while True:
            try:
                return self._features(params, PAGE)
            except psycopg.errors.DataError as exc:
                # Guard: an error that is not caused by individual rows must not exclude the whole window.
                bad_id = self._find_corrupt(params) if skipped < MAX_SKIPPED_PER_PAGE else None
                if bad_id is None:
                    raise
                skipped += 1
                self.excluded.add(bad_id)
                log.warning("reading %s skipped: stored content is not valid UTF-8 JSON (%s)", bad_id, exc)

    def _find_corrupt(self, params: dict) -> str | None:
        lo, hi = 1, PAGE  # invariant: the first `hi` rows fail; find the smallest failing prefix
        while lo < hi:
            mid = (lo + hi) // 2
            try:
                self._features(params, mid)
                lo = mid + 1
            except psycopg.errors.DataError:
                hi = mid
        with self.db.store.connection(timeout=10) as conn:
            row = conn.execute(READING_ID_AT_SQL, {**params, "excluded": sorted(self.excluded),
                                                   "offset": lo - 1}).fetchone()
        return row["reading_id"] if row else None

    def _load_new(self) -> int:
        min_period = datetime(1970, 1, 1, tzinfo=UTC)
        if not self.df.empty:
            min_period = self.df["period_start"].max() - timedelta(hours=self.settings.window_hours)
        else:
            with self.db.store.connection(timeout=10) as conn:
                newest = conn.execute("SELECT max(period_start) AS m FROM meter_reading_blob").fetchone()["m"]
            if newest is None:
                return 0
            min_period = newest - timedelta(hours=self.settings.window_hours)
        frames, loaded = [], 0
        while True:
            rows = self._fetch_page({"after_ts": self.cursor[0], "after_id": self.cursor[1],
                                     "min_period": min_period})
            if not rows:
                break
            self.cursor = (rows[-1]["created_at"], rows[-1]["reading_id"])
            frame = pd.DataFrame(rows).drop(columns=["created_at"])
            frames.append(frame)
            loaded += len(frame)
            if len(rows) < PAGE:
                break
        if frames:
            combined = pd.concat([self.df, *frames], ignore_index=True) if not self.df.empty else pd.concat(frames)
            self.df = self._normalise(combined)
        if not self.df.empty:
            cutoff = self.df["period_start"].max() - pd.Timedelta(hours=self.settings.window_hours)
            self.df = self.df[self.df["period_start"] >= cutoff].reset_index(drop=True)
        return loaded

    @staticmethod
    def _normalise(df: pd.DataFrame) -> pd.DataFrame:
        df = df.drop_duplicates("reading_id", keep="last").copy()
        for col in ("period_start", "period_end"):
            df[col] = pd.to_datetime(df[col], utc=True)
        for col in ("kwh_import", "kwh_export", "min_import", "v_min", "v_avg", "v_max"):
            df[col] = df[col].astype("float64")
        for col in ("seq", "n", "n_distinct"):
            df[col] = df[col].astype("int64")
        for col in CATEGORICAL:
            df[col] = df[col].astype("string").fillna("").astype("category")
        df["prosumer"] = df["prosumer"].astype(bool)
        return df.reset_index(drop=True)

    # ------------------------------------------------------------------ running
    def run(self, reason: str = "scheduled") -> dict:
        with self.lock:
            started = time.monotonic()
            if not self.db.ensure_schema():
                raise RuntimeError("meta-db indisponibil")
            loaded = self._load_new()
            detections = detect_all(self.df)
            persisted = self._persist(detections)
            self.detections = detections
            incidents = self._open_incidents()
            df = self.df
            self.last_run = {
                "at": _iso(datetime.now(UTC)),
                "reason": reason,
                "durationMs": int((time.monotonic() - started) * 1000),
                "readingsLoaded": loaded,
                "readingsAnalysed": len(df),
                "readingsSkippedCorrupt": len(self.excluded),
                "metersAnalysed": int(df["meter_id"].nunique()) if not df.empty else 0,
                "window": {"from": _iso(df["period_start"].min().to_pydatetime()) if not df.empty else None,
                           "to": _iso(df["period_end"].max().to_pydatetime()) if not df.empty else None,
                           "hours": self.settings.window_hours},
                "detections": len(detections),
                "findingsUpserted": persisted,
                "incidentsOpened": incidents,
            }
            log.info("analysis done: %s", self.last_run)
            return self.last_run

    def _persist(self, detections: list[Detection]) -> int:
        if not detections:
            return 0
        sql = """
        INSERT INTO grid_finding (id, fingerprint, type, title, meter_id, post, station, zone, region, window_start,
            window_end, first_seq, last_seq, observations, evidence, confidence, confidence_explanation,
            human_verification_required, data_origin)
        VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, 'SIMULATED')
        ON CONFLICT (fingerprint) DO UPDATE SET
            title = EXCLUDED.title, window_end = EXCLUDED.window_end, last_seq = EXCLUDED.last_seq,
            observations = EXCLUDED.observations, evidence = EXCLUDED.evidence, confidence = EXCLUDED.confidence,
            confidence_explanation = EXCLUDED.confidence_explanation, updated_at = now()
        WHERE grid_finding.last_seq IS DISTINCT FROM EXCLUDED.last_seq
           OR grid_finding.confidence IS DISTINCT FROM EXCLUDED.confidence
        """
        params = [
            (uuid.uuid4(), d.fingerprint, d.type, d.title[:255], d.meter_id, d.post, d.station, d.zone,
             self.topology.region_id(d.region), d.window_start, d.window_end, d.first_seq, d.last_seq,
             d.observations, Jsonb(d.evidence), d.confidence, d.confidence_explanation, d.human_check)
            for d in detections
        ]
        with self.db.meta.connection(timeout=10) as conn, conn.cursor() as cur:
            cur.executemany(sql, params)
        return len(params)

    def _open_incidents(self) -> int:
        """LOW_VOLTAGE at a post is a clustered finding (many meters at once): open an incident in the api."""
        with self.db.meta.connection(timeout=10) as conn:
            rows = conn.execute(
                f"SELECT {FINDING_COLUMNS} FROM grid_finding WHERE type = 'LOW_VOLTAGE' AND incident_id IS NULL "
                "AND status <> 'DISMISSED' AND confidence >= %s AND incident_attempts < 30 "
                "AND last_seq > first_seq ORDER BY created_at DESC LIMIT 10",
                (self.settings.incident_min_confidence,),
            ).fetchall()
        opened = 0
        for row in rows:
            payload = {
                "type": "GRID_ANOMALY",
                "severity": "HIGH" if row["confidence"] >= 0.9 else "MEDIUM",
                "certainty": "SUSPECTED",
                "source": "SYSTEM",
                "zone": row["zone"],
                "station": row["station"],
                "asset": row["post"],
                "title": texts.incident_title(row["type"], row["post"], row["station"])[:255],
                "description": texts.incident_description(row["observations"], row["human_verification_required"],
                                                          float(row["confidence"])),
                "dataOrigin": "SIMULATED",
                "fingerprint": f"grid-anomaly:{row['type']}:{row['post']}",
            }
            incident_id = self.api.create_incident(payload)
            with self.db.meta.connection(timeout=10) as conn:
                if incident_id:
                    conn.execute("UPDATE grid_finding SET incident_id = %s, updated_at = now() WHERE id = %s",
                                 (incident_id, row["id"]))
                    opened += 1
                else:
                    conn.execute("UPDATE grid_finding SET incident_attempts = incident_attempts + 1 WHERE id = %s",
                                 (row["id"],))
        return opened

    # ------------------------------------------------------------------ queries
    def list_findings(self, type_: str | None = None, status: str | None = None, region: str | None = None,
                      station: str | None = None, post: str | None = None, meter_id: str | None = None,
                      limit: int = 50) -> list[dict]:
        where, params = ["1=1"], []
        for column, value in (("type", type_), ("status", status), ("region", self.topology.region_id(region)),
                              ("station", station), ("post", post), ("meter_id", meter_id)):
            if value:
                where.append(f"{column} = %s")
                params.append(value.upper() if column in ("type", "status") else value)
        params.append(max(1, min(limit, 500)))
        with self.db.meta.connection(timeout=10) as conn:
            rows = conn.execute(
                f"SELECT {FINDING_COLUMNS} FROM grid_finding WHERE {' AND '.join(where)} "
                "ORDER BY updated_at DESC LIMIT %s", params).fetchall()
        return [finding_json(r, self.topology) for r in rows]

    def get_finding(self, finding_id: str) -> dict | None:
        try:
            uuid.UUID(finding_id)
        except ValueError:
            return None
        with self.db.meta.connection(timeout=10) as conn:
            row = conn.execute(f"SELECT {FINDING_COLUMNS} FROM grid_finding WHERE id = %s", (finding_id,)).fetchone()
        return finding_json(row, self.topology) if row else None

    def set_status(self, finding_id: str, status: str, note: str | None) -> dict | None:
        if self.get_finding(finding_id) is None:
            return None
        with self.db.meta.connection(timeout=10) as conn:
            conn.execute("UPDATE grid_finding SET status = %s, status_note = %s, updated_at = now() WHERE id = %s",
                         (status, note, finding_id))
        return self.get_finding(finding_id)

    def summary(self) -> dict:
        with self.db.meta.connection(timeout=10) as conn:
            by_type = conn.execute("SELECT type, count(*) AS c FROM grid_finding GROUP BY type").fetchall()
            by_region = conn.execute("SELECT region, count(*) AS c FROM grid_finding GROUP BY region").fetchall()
            by_status = conn.execute("SELECT status, count(*) AS c FROM grid_finding GROUP BY status").fetchall()
            incidents = conn.execute(
                "SELECT count(*) AS c FROM grid_finding WHERE incident_id IS NOT NULL").fetchone()["c"]
        df = self.df
        return {
            "total": sum(r["c"] for r in by_type),
            "byType": {r["type"]: r["c"] for r in by_type},
            "byTypeLabel": {texts.TYPE_LABELS.get(r["type"], r["type"]): r["c"] for r in by_type},
            "byRegion": {(self.topology.region_name(r["region"]) or "—"): r["c"] for r in by_region},
            "byStatus": {r["status"]: r["c"] for r in by_status},
            "incidentsOpened": incidents,
            "metersAnalysed": int(df["meter_id"].nunique()) if not df.empty else 0,
            "readingsAnalysed": len(df),
            "prosumers": int(df.loc[df["prosumer"], "meter_id"].nunique()) if not df.empty else 0,
            "lastRun": self.last_run,
            "dataOrigin": "SIMULATED",
        }

    def grid(self) -> dict:
        df = self.df
        since = None
        if not df.empty:
            since = df["period_start"].max().to_pydatetime() - timedelta(hours=6)
        with self.db.meta.connection(timeout=10) as conn:
            rows = conn.execute(
                "SELECT post, type, count(*) AS c, max(confidence) AS conf FROM grid_finding "
                "WHERE status <> 'DISMISSED' AND post IS NOT NULL AND (%s::timestamptz IS NULL OR window_end >= %s) "
                "GROUP BY post, type", (since, since)).fetchall()
        per_post: dict[str, dict] = {}
        for r in rows:
            per_post.setdefault(r["post"], {})[r["type"]] = int(r["c"])
        meters = df.groupby("transformer", observed=True)["meter_id"].nunique().to_dict() if not df.empty else {}
        rank = {"OK": 0, "WARNING": 1, "CRITICAL": 2}
        posts, station_status = [], {}
        for p in self.topology.posts:
            counts = per_post.get(p["id"], {})
            status = "OK"
            if counts:
                status = "CRITICAL" if counts.get("LOW_VOLTAGE") or counts.get("IMPOSSIBLE_VALUES") else "WARNING"
            if rank[status] > rank[station_status.get(p["station"], "OK")]:
                station_status[p["station"]] = status
            posts.append({**p, "meters": int(meters.get(p["id"], 0)), "status": status,
                          "findings": counts, "findingsTotal": sum(counts.values())})
        stations = [{**s, "status": station_status.get(s["id"], "OK")} for s in self.topology.stations]
        region_status = {}
        for s in stations:
            if rank[s["status"]] > rank[region_status.get(s["region"], "OK")]:
                region_status[s["region"]] = s["status"]
        regions = [{**r, "status": region_status.get(r["id"], "OK")} for r in self.topology.regions]
        links = ([{"from": s["region"], "to": s["id"], "kind": "region-station"} for s in stations]
                 + [{"from": p["station"], "to": p["id"], "kind": "station-post"} for p in posts])
        return {
            "canvas": self.topology.canvas, "regions": regions, "stations": stations, "posts": posts,
            "links": links, "activeSince": _iso(since), "lastRun": self.last_run, "dataOrigin": "SIMULATED",
            "statusLegend": {"OK": "Fără constatări", "WARNING": "Constatări la contoare",
                             "CRITICAL": "Constatare la nivel de post sau valori imposibile"},
        }

    def evaluation(self) -> dict:
        path: Path = self.settings.ground_truth_path
        if not path.exists():
            return {"available": False, "reason": "Fișierul injected_anomalies.json al simulatorului nu există.",
                    "dataOrigin": "SIMULATED"}
        if self.df.empty:
            return {"available": False, "reason": "Nu există încă citiri analizate.", "dataOrigin": "SIMULATED"}
        truth = json.loads(path.read_text(encoding="utf-8"))
        result = evaluate(self.detections, truth, int(self.df["seq"].min()), int(self.df["seq"].max()))
        result["lastRun"] = self.last_run
        return result
