package ro.filegrid.file;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FileContextRepository extends JpaRepository<FileContext, UUID> {
}
