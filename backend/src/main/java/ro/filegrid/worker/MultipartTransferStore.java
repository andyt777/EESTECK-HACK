package ro.filegrid.worker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import ro.filegrid.storage.s3.MultipartCheckpoint;

/**
 * Keeps S3 multipart progress in {@code s3_multipart_transfer} (meta-db), one
 * row per file. Every confirmed part is committed immediately, so a crash loses
 * at most the part that was in flight.
 */
@Component
public class MultipartTransferStore {

    private static final TypeReference<List<MultipartCheckpoint.ConfirmedPart>> PARTS = new TypeReference<>() {
    };

    private final JdbcTemplate metaDb;
    private final ObjectMapper json;

    public MultipartTransferStore(JdbcTemplate metaDb, ObjectMapper json) {
        this.metaDb = metaDb;
        this.json = json;
    }

    /** The checkpoint of one file's transfer. */
    public MultipartCheckpoint forFile(UUID fileId, Runnable onResume) {
        return new MultipartCheckpoint() {
            @Override
            public Optional<State> load() {
                return find(fileId);
            }

            @Override
            public void begin(String bucket, String storageKey, String uploadId, long partSize, int totalParts) {
                metaDb.update("""
                        INSERT INTO s3_multipart_transfer
                            (file_id, upload_id, bucket, storage_key, part_size, total_parts, confirmed_parts)
                        VALUES (?, ?, ?, ?, ?, ?, '[]'::jsonb)
                        ON CONFLICT (file_id) DO UPDATE
                            SET upload_id = EXCLUDED.upload_id, bucket = EXCLUDED.bucket,
                                storage_key = EXCLUDED.storage_key, part_size = EXCLUDED.part_size,
                                total_parts = EXCLUDED.total_parts, confirmed_parts = '[]'::jsonb,
                                created_at = now(), updated_at = now()
                        """, fileId, uploadId, bucket, storageKey, partSize, totalParts);
            }

            @Override
            public void partConfirmed(ConfirmedPart part) {
                metaDb.update("""
                        UPDATE s3_multipart_transfer
                           SET confirmed_parts = confirmed_parts || ?::jsonb, updated_at = now()
                         WHERE file_id = ?
                        """, write(List.of(part)), fileId);
            }

            @Override
            public void clear() {
                metaDb.update("DELETE FROM s3_multipart_transfer WHERE file_id = ?", fileId);
            }

            @Override
            public void resumed(long bytesAlreadyStored) {
                if (bytesAlreadyStored > 0) {
                    onResume.run();
                }
            }
        };
    }

    public Optional<MultipartCheckpoint.State> find(UUID fileId) {
        return metaDb.query("""
                        SELECT upload_id, bucket, storage_key, part_size, total_parts, confirmed_parts::text AS parts
                          FROM s3_multipart_transfer WHERE file_id = ?
                        """,
                (rs, i) -> new MultipartCheckpoint.State(rs.getString("upload_id"), rs.getString("bucket"),
                        rs.getString("storage_key"), rs.getLong("part_size"), rs.getInt("total_parts"),
                        read(rs.getString("parts"))),
                fileId).stream().findFirst();
    }

    public void delete(UUID fileId) {
        metaDb.update("DELETE FROM s3_multipart_transfer WHERE file_id = ?", fileId);
    }

    private List<MultipartCheckpoint.ConfirmedPart> read(String parts) {
        try {
            return json.readValue(parts, PARTS);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unreadable confirmed_parts: " + e.getMessage(), e);
        }
    }

    private String write(List<MultipartCheckpoint.ConfirmedPart> parts) {
        try {
            return json.writeValueAsString(parts);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
