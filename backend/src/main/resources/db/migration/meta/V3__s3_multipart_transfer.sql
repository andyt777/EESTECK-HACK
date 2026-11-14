-- Progress of an S3 multipart upload, saved part by part, so that a worker
-- that crashes (or loses S3) continues from the last confirmed part instead of
-- sending the whole file again.
CREATE TABLE s3_multipart_transfer (
    file_id         UUID PRIMARY KEY REFERENCES file_record (id) ON DELETE CASCADE,
    upload_id       VARCHAR(1024) NOT NULL,
    bucket          VARCHAR(255)  NOT NULL,
    -- Full FileGrid storage key (segment/yyyy/MM/dd/uuid), not the key inside the bucket.
    storage_key     VARCHAR(512)  NOT NULL,
    part_size       BIGINT        NOT NULL CHECK (part_size > 0),
    total_parts     INTEGER       NOT NULL CHECK (total_parts > 0),
    -- [{"partNumber": 1, "etag": "...", "size": 16777216}, ...] in upload order.
    confirmed_parts JSONB         NOT NULL DEFAULT '[]'::jsonb,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);
