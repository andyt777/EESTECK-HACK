package ro.filegrid.file;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FileEventRepository extends JpaRepository<FileEvent, Long> {

    List<FileEvent> findByFileIdOrderByAtAsc(UUID fileId);
}
