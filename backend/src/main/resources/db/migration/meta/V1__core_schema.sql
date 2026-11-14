-- FileGrid metadata schema (meta-db).
-- Enumerations are stored as text with CHECK constraints so that invalid
-- states can never be persisted, whatever the client.

CREATE TABLE file_record (
    id               UUID PRIMARY KEY,
    category         VARCHAR(32)  NOT NULL
        CHECK (category IN ('METER_READING', 'FAULT_RECORD', 'CONNECTION_DOCUMENT', 'DRONE_VIDEO')),
    original_name    VARCHAR(512) NOT NULL,
    content_type     VARCHAR(255),
    size_bytes       BIGINT       NOT NULL CHECK (size_bytes >= 0),
    sha256_plain     VARCHAR(64),
    sha256_stored    VARCHAR(64),
    status           VARCHAR(32)  NOT NULL
        CHECK (status IN ('RECEIVED', 'ANALYZING', 'CATEGORIZED', 'DESTINATION_SELECTED', 'TRANSFERRING',
                          'TRANSFER_RESUMED', 'STORED_VERIFIED', 'PENDING', 'TRANSFER_FAILED')),
    integrity_status VARCHAR(32)  NOT NULL DEFAULT 'UNVERIFIED'
        CHECK (integrity_status IN ('UNVERIFIED', 'VERIFIED_INTACT', 'MISMATCH')),
    last_verified_at TIMESTAMPTZ,
    storage_type     VARCHAR(16)
        CHECK (storage_type IN ('FTP', 'S3', 'DATABASE')),
    storage_key      VARCHAR(512),
    encrypted        BOOLEAN      NOT NULL DEFAULT FALSE,
    sensitivity      VARCHAR(32)  NOT NULL DEFAULT 'NONE'
        CHECK (sensitivity IN ('NONE', 'PERSONAL_DATA')),
    data_origin      VARCHAR(16)  NOT NULL
        CHECK (data_origin IN ('REAL', 'SIMULATED')),
    source           VARCHAR(16)  NOT NULL
        CHECK (source IN ('UI', 'SIMULATOR', 'API')),
    uploaded_by      VARCHAR(255) NOT NULL,
    received_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    stored_at        TIMESTAMPTZ,
    idempotency_key  VARCHAR(128) UNIQUE,
    trace_id         VARCHAR(64)
);

CREATE INDEX idx_file_record_category_received ON file_record (category, received_at DESC);
CREATE INDEX idx_file_record_status ON file_record (status);
CREATE INDEX idx_file_record_received_at ON file_record (received_at DESC);

-- Operational context: every column is optional (only what applies is filled in).
CREATE TABLE file_context (
    file_id     UUID PRIMARY KEY REFERENCES file_record (id) ON DELETE CASCADE,
    zone        VARCHAR(128),
    station     VARCHAR(128),
    equipment   VARCHAR(128),
    meter_id    VARCHAR(64),
    event_type  VARCHAR(64),
    event_time  TIMESTAMPTZ,
    description TEXT
);

CREATE INDEX idx_file_context_zone ON file_context (zone);
CREATE INDEX idx_file_context_station ON file_context (station);
CREATE INDEX idx_file_context_meter ON file_context (meter_id);

-- Agent 01 decision, saved BEFORE the transfer starts.
CREATE TABLE routing_decision (
    id               UUID PRIMARY KEY,
    file_id          UUID         NOT NULL REFERENCES file_record (id) ON DELETE CASCADE,
    policy_version   VARCHAR(64)  NOT NULL,
    detection        JSONB        NOT NULL DEFAULT '{}'::jsonb,
    features         JSONB        NOT NULL DEFAULT '{}'::jsonb,
    candidates       JSONB        NOT NULL DEFAULT '[]'::jsonb,
    selected_storage VARCHAR(16)
        CHECK (selected_storage IN ('FTP', 'S3', 'DATABASE')),
    fallback_used    BOOLEAN      NOT NULL DEFAULT FALSE,
    fallback_reason  TEXT,
    explanation_ro   TEXT,
    decided_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_routing_decision_file ON routing_decision (file_id, decided_at DESC);

-- Audit trail: append-only.
CREATE TABLE file_event (
    id       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    file_id  UUID        NOT NULL REFERENCES file_record (id) ON DELETE CASCADE,
    type     VARCHAR(32) NOT NULL
        CHECK (type IN ('RECEIVED', 'CLASSIFIED', 'ROUTED', 'TRANSFER_STARTED', 'TRANSFER_RESUMED', 'STORED',
                        'VERIFIED', 'ACCESSED', 'DOWNLOADED', 'STATUS_CHANGED', 'FALLBACK', 'INTEGRITY_INCIDENT')),
    actor    VARCHAR(255),
    details  JSONB       NOT NULL DEFAULT '{}'::jsonb,
    trace_id VARCHAR(64),
    at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_file_event_file_at ON file_event (file_id, at);

CREATE FUNCTION file_event_reject_change() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'file_event is append-only: % is not allowed', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER file_event_append_only
    BEFORE UPDATE ON file_event
    FOR EACH ROW EXECUTE FUNCTION file_event_reject_change();

-- Last known state of each storage, refreshed by the scheduled health check.
CREATE TABLE storage_health (
    storage    VARCHAR(16) PRIMARY KEY
        CHECK (storage IN ('FTP', 'S3', 'DATABASE')),
    status     VARCHAR(16) NOT NULL
        CHECK (status IN ('UP', 'DOWN')),
    last_check TIMESTAMPTZ NOT NULL,
    latency_ms BIGINT      NOT NULL,
    detail     TEXT
);
