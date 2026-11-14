-- Meter ingestion (Stage 5).

-- One row per accepted batch, keyed by the client's Idempotency-Key: a batch
-- sent again with the same key gets the same answer and is not queued again.
CREATE TABLE meter_batch (
    idempotency_key VARCHAR(128) PRIMARY KEY,
    request_sha256  VARCHAR(64)  NOT NULL,
    reading_count   INTEGER      NOT NULL,
    accepted_count  INTEGER      NOT NULL,
    duplicate_count INTEGER      NOT NULL,
    response        JSONB        NOT NULL,
    data_origin     VARCHAR(16)  NOT NULL CHECK (data_origin IN ('REAL', 'SIMULATED')),
    received_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_meter_batch_received ON meter_batch (received_at);

-- Running totals written by every api / worker instance (one UPDATE per batch),
-- read by the reconciliation job that publishes the metrics.
CREATE TABLE meter_counter (
    name       VARCHAR(64) PRIMARY KEY,
    value      BIGINT      NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO meter_counter (name) VALUES
    ('readings_stored'),
    ('duplicates_rejected'),
    ('duplicates_written');
