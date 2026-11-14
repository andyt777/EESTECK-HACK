-- Security & Incident Center: incidents, their files and timeline, and the
-- notifications sent (or simulated) for them.

CREATE SEQUENCE incident_code_seq START 1;

CREATE TABLE incident (
    id          UUID PRIMARY KEY,
    code        VARCHAR(32)  NOT NULL UNIQUE,
    type        VARCHAR(48)  NOT NULL
        CHECK (type IN ('UNAUTHORIZED_ACCESS', 'DIRECT_STORAGE_ACCESS_ATTEMPT', 'INTEGRITY_MISMATCH',
                        'STORAGE_UNAVAILABLE', 'TRANSFER_FAILED', 'BUSINESS_SLA_AT_RISK', 'GRID_FAULT_MAJOR',
                        'FIRE_REPORTED', 'FIELD_EMERGENCY', 'CYBER_SECURITY', 'GRID_ANOMALY')),
    severity    VARCHAR(16)  NOT NULL CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    status      VARCHAR(16)  NOT NULL
        CHECK (status IN ('NEW', 'NOTIFIED', 'ACKNOWLEDGED', 'IN_PROGRESS', 'RESOLVED', 'CLOSED')),
    certainty   VARCHAR(16)  NOT NULL CHECK (certainty IN ('CONFIRMED', 'REPORTED', 'SUSPECTED')),
    source      VARCHAR(16)  NOT NULL CHECK (source IN ('ALERTMANAGER', 'SYSTEM', 'USER', 'SENSOR')),
    zone        VARCHAR(128),
    station     VARCHAR(128),
    asset       VARCHAR(128),
    title       VARCHAR(255) NOT NULL,
    description TEXT,
    owner       VARCHAR(255),
    data_origin VARCHAR(16)  NOT NULL CHECK (data_origin IN ('REAL', 'SIMULATED')),
    -- Identifies the underlying condition, so the same problem does not open two incidents.
    fingerprint VARCHAR(255),
    attributes  JSONB        NOT NULL DEFAULT '{}'::jsonb,
    resolution  TEXT,
    created_by  VARCHAR(255) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    resolved_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX uq_incident_open_fingerprint ON incident (fingerprint)
    WHERE fingerprint IS NOT NULL AND status NOT IN ('RESOLVED', 'CLOSED');
CREATE INDEX idx_incident_created ON incident (created_at DESC);
CREATE INDEX idx_incident_status ON incident (status, severity);

CREATE TABLE incident_file (
    incident_id UUID NOT NULL REFERENCES incident (id) ON DELETE CASCADE,
    file_id     UUID NOT NULL REFERENCES file_record (id) ON DELETE CASCADE,
    PRIMARY KEY (incident_id, file_id)
);
CREATE INDEX idx_incident_file_file ON incident_file (file_id);

-- Timeline: append-only.
CREATE TABLE incident_event (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    incident_id UUID        NOT NULL REFERENCES incident (id) ON DELETE CASCADE,
    at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor       VARCHAR(255),
    kind        VARCHAR(32) NOT NULL,
    message     TEXT        NOT NULL
);
CREATE INDEX idx_incident_event_incident ON incident_event (incident_id, at);

CREATE TABLE notification (
    id                UUID PRIMARY KEY,
    incident_id       UUID         NOT NULL REFERENCES incident (id) ON DELETE CASCADE,
    kind              VARCHAR(16)  NOT NULL CHECK (kind IN ('ALERT', 'UPDATE')),
    recipient_name    VARCHAR(255) NOT NULL,
    role              VARCHAR(32)  NOT NULL,
    -- Only ever a masked form: the real number stays in the environment.
    phone_masked      VARCHAR(32),
    channel           VARCHAR(16)  NOT NULL DEFAULT 'WHATSAPP',
    mode              VARCHAR(16)  NOT NULL CHECK (mode IN ('SIMULATED', 'PREPARED_ONLY', 'REAL')),
    status            VARCHAR(16)  NOT NULL
        CHECK (status IN ('PREPARED', 'SIMULATED', 'SENT', 'DELIVERED', 'CONFIRMED', 'ESCALATED', 'CLOSED')),
    level             INTEGER      NOT NULL DEFAULT 1,
    body              TEXT         NOT NULL,
    provider_id       VARCHAR(64),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sent_at           TIMESTAMPTZ,
    delivered_at      TIMESTAMPTZ,
    confirmed_at      TIMESTAMPTZ,
    confirmed_by      VARCHAR(255),
    escalated_at      TIMESTAMPTZ,
    escalation_due_at TIMESTAMPTZ
);
CREATE INDEX idx_notification_incident ON notification (incident_id, created_at);
CREATE INDEX idx_notification_created ON notification (created_at DESC);
CREATE INDEX idx_notification_due ON notification (escalation_due_at)
    WHERE status IN ('PREPARED', 'SIMULATED', 'SENT', 'DELIVERED');
