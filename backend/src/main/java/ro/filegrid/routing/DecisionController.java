package ro.filegrid.routing;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ro.filegrid.file.FileCategory;
import ro.filegrid.file.FileRecord;
import ro.filegrid.file.FileRecordRepository;
import ro.filegrid.file.FileStatus;
import ro.filegrid.file.RoutingDecision;
import ro.filegrid.file.RoutingDecisionRepository;
import ro.filegrid.security.AccessPolicy;
import ro.filegrid.security.CurrentActorProvider;
import ro.filegrid.storage.StorageType;
import ro.filegrid.text.Texts;

/** The routing decision of a file, as saved by Agent 01 (data for the Decision Explorer). */
@RestController
@Profile("api")
public class DecisionController {

    public static final String PERMISSION_READ = "file:read";

    private final FileRecordRepository files;
    private final RoutingDecisionRepository decisions;
    private final CurrentActorProvider actor;
    private final AccessPolicy accessPolicy;
    private final Texts texts;

    public DecisionController(FileRecordRepository files, RoutingDecisionRepository decisions,
                              CurrentActorProvider actor, AccessPolicy accessPolicy, Texts texts) {
        this.texts = texts;
        this.files = files;
        this.decisions = decisions;
        this.actor = actor;
        this.accessPolicy = accessPolicy;
    }

    @GetMapping("/files/{id}/decision")
    public DecisionView decision(@PathVariable UUID id) {
        if (!accessPolicy.isAllowed(actor.currentActor(), PERMISSION_READ)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        FileRecord file = files.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, texts.get("decision.error.fileNotFound")));
        RoutingDecision decision = decisions.findFirstByFileIdOrderByDecidedAtDesc(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        texts.get("decision.error.noDecision")));
        return new DecisionView(decision.getId(), file.getId(), file.getCategory(), file.getStatus(),
                decision.getPolicyVersion(), decision.getDetection(), decision.getFeatures(),
                decision.getCandidates(), decision.getSelectedStorage(), decision.isFallbackUsed(),
                decision.getFallbackReason(), decision.getExplanationRo(), decision.getDecidedAt());
    }

    /**
     * @param fileStatus      current state of the file (the final result of the operation)
     * @param detection       how the category was established
     * @param features        the values the decision was based on
     * @param candidates      every storage evaluated, with eligibility, score and reasons
     * @param selectedStorage chosen storage, or {@code null} when the file is waiting
     */
    public record DecisionView(UUID id, UUID fileId, FileCategory category, FileStatus fileStatus,
                               String policyVersion, JsonNode detection, JsonNode features, JsonNode candidates,
                               StorageType selectedStorage, boolean fallbackUsed, String fallbackReason,
                               String explanationRo, Instant decidedAt) {
    }
}
