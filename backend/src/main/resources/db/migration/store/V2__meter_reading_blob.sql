-- Meter readings in the "Database" storage (store-db). One row per reading.
-- reading_id = SHA-256(meterId|periodStart): the PRIMARY KEY is what makes a
-- reading impossible to store twice (ingestion uses ON CONFLICT DO NOTHING).
CREATE TABLE meter_reading_blob (
    reading_id     VARCHAR(64)  PRIMARY KEY,
    meter_id       VARCHAR(64)  NOT NULL,
    period_start   TIMESTAMPTZ  NOT NULL,
    period_end     TIMESTAMPTZ  NOT NULL,
    seq            BIGINT       NOT NULL,
    region         VARCHAR(64),
    zone           VARCHAR(128),
    station        VARCHAR(128),
    transformer    VARCHAR(128),
    data_origin    VARCHAR(16)  NOT NULL CHECK (data_origin IN ('REAL', 'SIMULATED')),
    -- The reading exactly as received (a small JSON file, 1-50 KB).
    content        BYTEA        NOT NULL,
    size_bytes     INTEGER      NOT NULL CHECK (size_bytes >= 0),
    sha256         VARCHAR(64)  NOT NULL,
    batch_key      VARCHAR(128),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- Belt and braces: even a bug in the id computation cannot store one period twice.
    CONSTRAINT meter_reading_one_per_period UNIQUE (meter_id, period_start)
);

CREATE INDEX idx_meter_reading_created ON meter_reading_blob (created_at);
CREATE INDEX idx_meter_reading_meter_seq ON meter_reading_blob (meter_id, seq);
