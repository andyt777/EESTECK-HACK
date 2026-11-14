-- The "Database" storage (store-db): objects kept as bytea rows.

CREATE TABLE stored_blob (
    key        VARCHAR(512) PRIMARY KEY,
    content    BYTEA       NOT NULL,
    size       BIGINT      NOT NULL CHECK (size >= 0),
    sha256     VARCHAR(64)    NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- EXTERNAL = out-of-line and uncompressed. Reads fetch the object in slices
-- with substring(), which is only cheap when the value is not compressed.
ALTER TABLE stored_blob ALTER COLUMN content SET STORAGE EXTERNAL;
