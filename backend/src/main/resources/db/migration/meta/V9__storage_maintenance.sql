-- Planned maintenance of a storage (admin, policy:write). While a row exists, Agent 01 treats the storage as
-- not available for new routing decisions and says why; files fall back on the policy chain. Not a failure:
-- the storage keeps answering its health probe, so no StorageDown alert or incident is raised.
CREATE TABLE storage_maintenance (
    storage     VARCHAR(16)  PRIMARY KEY CHECK (storage IN ('DATABASE', 'S3', 'FTP')),
    reason      VARCHAR(200) NOT NULL,
    started_by  VARCHAR(255) NOT NULL,
    started_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
