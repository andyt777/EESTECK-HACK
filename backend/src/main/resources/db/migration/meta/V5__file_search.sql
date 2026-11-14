-- Search (GET /api/files): trigram indexes for "contains" text search, plus
-- the columns used as filters.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX idx_file_record_name_trgm ON file_record USING gin (original_name gin_trgm_ops);
CREATE INDEX idx_file_context_description_trgm ON file_context USING gin (description gin_trgm_ops);
CREATE INDEX idx_file_context_station_trgm ON file_context USING gin (station gin_trgm_ops);

CREATE INDEX idx_file_record_integrity ON file_record (integrity_status) WHERE integrity_status <> 'VERIFIED_INTACT';
CREATE INDEX idx_file_record_storage ON file_record (storage_type, received_at DESC);
CREATE INDEX idx_file_record_size ON file_record (size_bytes);
-- Non-meter files are few compared with readings: lists that skip readings stay fast.
CREATE INDEX idx_file_record_non_meter ON file_record (received_at DESC) WHERE category <> 'METER_READING';
CREATE INDEX idx_file_record_last_verified ON file_record (last_verified_at NULLS FIRST)
    WHERE status = 'STORED_VERIFIED' AND category <> 'METER_READING';
