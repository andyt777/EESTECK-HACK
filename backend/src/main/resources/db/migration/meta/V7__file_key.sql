-- Envelope encryption (Stage 7): the data key (DEK) of each encrypted file,
-- wrapped with the key-encryption key (KEK) named by key_id. Kept in meta-db,
-- never next to the object in the storage.
CREATE TABLE file_key (
    file_id     UUID PRIMARY KEY REFERENCES file_record (id) ON DELETE CASCADE,
    key_id      VARCHAR(64) NOT NULL,
    algorithm   VARCHAR(64) NOT NULL,
    wrapped_dek BYTEA       NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
