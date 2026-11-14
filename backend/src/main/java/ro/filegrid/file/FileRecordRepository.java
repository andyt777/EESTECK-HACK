package ro.filegrid.file;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FileRecordRepository extends JpaRepository<FileRecord, UUID> {

    Optional<FileRecord> findByIdempotencyKey(String idempotencyKey);

    /** Oldest files in one of the given states (used to resume PENDING and interrupted work). */
    List<FileRecord> findTop100ByStatusInOrderByReceivedAtAsc(Collection<FileStatus> statuses);
}
