package ro.filegrid.file;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoutingDecisionRepository extends JpaRepository<RoutingDecision, UUID> {

    /** The decision currently in force for a file (the most recent one). */
    Optional<RoutingDecision> findFirstByFileIdOrderByDecidedAtDesc(UUID fileId);
}
