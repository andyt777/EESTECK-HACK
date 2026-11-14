"""Database access: meta-db (own tables, read/write) and store-db (meter readings, read-only queries)."""

from __future__ import annotations

import logging

from psycopg.rows import dict_row
from psycopg_pool import ConnectionPool

log = logging.getLogger(__name__)

SCHEMA = """
CREATE TABLE IF NOT EXISTS grid_finding (
    id                         UUID PRIMARY KEY,
    fingerprint                VARCHAR(255) NOT NULL UNIQUE,
    type                       VARCHAR(32)  NOT NULL,
    title                      VARCHAR(255) NOT NULL,
    meter_id                   VARCHAR(64),
    post                       VARCHAR(128),
    station                    VARCHAR(128),
    zone                       VARCHAR(128),
    region                     VARCHAR(64),
    window_start               TIMESTAMPTZ,
    window_end                 TIMESTAMPTZ,
    first_seq                  BIGINT,
    last_seq                   BIGINT,
    observations               TEXT         NOT NULL,
    evidence                   JSONB        NOT NULL DEFAULT '{}'::jsonb,
    confidence                 REAL         NOT NULL CHECK (confidence >= 0 AND confidence <= 1),
    confidence_explanation     TEXT,
    human_verification_required TEXT        NOT NULL,
    data_origin                VARCHAR(16)  NOT NULL DEFAULT 'SIMULATED'
        CHECK (data_origin IN ('REAL', 'SIMULATED')),
    status                     VARCHAR(16)  NOT NULL DEFAULT 'NEW'
        CHECK (status IN ('NEW', 'CONFIRMED', 'DISMISSED')),
    status_note                TEXT,
    incident_id                VARCHAR(64),
    incident_attempts          INTEGER      NOT NULL DEFAULT 0,
    created_at                 TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                 TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_grid_finding_created ON grid_finding (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_grid_finding_type ON grid_finding (type, status);
CREATE INDEX IF NOT EXISTS idx_grid_finding_post ON grid_finding (post);

CREATE TABLE IF NOT EXISTS voice_call (
    id          UUID PRIMARY KEY,
    incident_id VARCHAR(64)  NOT NULL,
    role        VARCHAR(64)  NOT NULL,
    provider    VARCHAR(32)  NOT NULL,
    script      TEXT         NOT NULL,
    status      VARCHAR(16)  NOT NULL CHECK (status IN ('SIMULATED', 'QUEUED', 'COMPLETED', 'FAILED')),
    transcript  TEXT,
    data_origin VARCHAR(16)  NOT NULL DEFAULT 'SIMULATED' CHECK (data_origin IN ('REAL', 'SIMULATED')),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_voice_call_incident ON voice_call (incident_id, created_at DESC);
"""


class Database:
    def __init__(self, meta_dsn: str, store_dsn: str):
        self.meta = ConnectionPool(meta_dsn, min_size=1, max_size=4, open=False, kwargs={"row_factory": dict_row})
        self.store = ConnectionPool(store_dsn, min_size=1, max_size=2, open=False, kwargs={"row_factory": dict_row})
        self.schema_ready = False

    def open(self) -> None:
        self.meta.open(wait=False)
        self.store.open(wait=False)

    def close(self) -> None:
        self.meta.close()
        self.store.close()

    def ensure_schema(self) -> bool:
        if self.schema_ready:
            return True
        try:
            with self.meta.connection(timeout=10) as conn:
                conn.execute(SCHEMA)
            self.schema_ready = True
            log.info("intelligence schema ready")
        except Exception as exc:  # noqa: BLE001 - retried by the caller
            log.warning("meta-db not ready: %s", exc)
        return self.schema_ready
