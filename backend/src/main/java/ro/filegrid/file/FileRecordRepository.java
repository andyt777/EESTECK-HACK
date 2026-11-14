package ro.filegrid.file;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FileRecordRepository extends JpaRepository<FileRecord, UUID> {

    Optional<FileRecord> findByIdempotencyKey(String idempotencyKey);
}
