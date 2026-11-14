-- Versions of the routing policy (Agent 01). The active policy is the row with
-- the highest version, whether it came from config/policies.yaml (FILE) or
-- from the admin API (ADMIN). Rows are never updated.
CREATE TABLE policy_version (
    version        BIGINT       PRIMARY KEY CHECK (version > 0),
    source         VARCHAR(16)  NOT NULL
        CHECK (source IN ('FILE', 'ADMIN')),
    author         VARCHAR(255) NOT NULL,
    content        TEXT         NOT NULL,
    content_sha256 VARCHAR(64)  NOT NULL,
    -- Changes against the previous version: [{"path": ..., "before": ..., "after": ...}]
    diff           JSONB        NOT NULL DEFAULT '[]'::jsonb,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_policy_version_source ON policy_version (source, version DESC);
