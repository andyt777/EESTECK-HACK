package ro.filegrid.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.filegrid.classification.ClassificationResult;
import ro.filegrid.file.FileCategory;
import ro.filegrid.file.FileEvent;
import ro.filegrid.file.FileEventRepository;
import ro.filegrid.file.FileEventType;
import ro.filegrid.file.FileRecord;
import ro.filegrid.file.FileRecordRepository;
import ro.filegrid.file.FileStatus;
import ro.filegrid.file.RoutingDecision;
import ro.filegrid.file.RoutingDecisionRepository;
import ro.filegrid.policy.Policy;
import ro.filegrid.policy.Policy.CategoryPolicy;
import ro.filegrid.policy.PolicyService;
import ro.filegrid.policy.PolicyService.ActivePolicy;
import ro.filegrid.storage.StorageKeys;
import ro.filegrid.storage.StorageProperties;
import ro.filegrid.storage.StorageType;
import ro.filegrid.text.Sizes;

/**
 * Agent 01: decides where a classified file goes and records the decision
 * BEFORE any transfer starts, together with everything it was based on
 * (detection, the values used, every candidate with its reasons and score).
 *
 * <p>When no storage is eligible and available, the file becomes PENDING and
 * the reason is saved; nothing is lost and nothing is reported as stored.
 */
@Service
public class RoutingService {

    /** Actor recorded in the audit trail for decisions taken by the routing agent. */
    public static final String AGENT_ACTOR = "agent-01";

    private static final Logger log = LoggerFactory.getLogger(RoutingService.class);

    private final PolicyService policies;
    private final RoutingEngine engine;
    private final StorageStateProvider storageStates;
    private final FileRecordRepository files;
    private final RoutingDecisionRepository decisions;
    private final FileEventRepository events;
    private final ObjectMapper json;
    private final Map<StorageType, Long> adapterLimits = new EnumMap<>(StorageType.class);

    public RoutingService(PolicyService policies, RoutingEngine engine, StorageStateProvider storageStates,
                          FileRecordRepository files, RoutingDecisionRepository decisions,
                          FileEventRepository events, ObjectMapper json, StorageProperties storageProperties) {
        this.policies = policies;
        this.engine = engine;
        this.storageStates = storageStates;
        this.files = files;
        this.decisions = decisions;
        this.events = events;
        this.json = json;
        this.adapterLimits.put(StorageType.DATABASE, storageProperties.database().maxObjectBytes());
    }

    /**
     * Routes a file that has been classified.
     *
     * @return the saved decision; {@code getSelectedStorage()} is {@code null} when the file is PENDING
     * @throws NoSuchElementException   when the file does not exist
     * @throws IllegalArgumentException when the classification found no category
     */
    @Transactional
    public RoutingDecision route(UUID fileId, ClassificationResult classification) {
        FileRecord file = files.findById(fileId)
                .orElseThrow(() -> new NoSuchElementException("Unknown file " + fileId));
        FileCategory category = classification.category();
        if (category == null) {
            throw new IllegalArgumentException("File " + fileId + " has no category and cannot be routed");
        }
        ActivePolicy active = policies.active();
        Policy policy = active.policy();
        Map<StorageType, StorageState> states = storageStates.currentStates();
        Instant now = Instant.now();

        RoutingOutcome outcome = engine.evaluate(policy, category, file.getSizeBytes(), states, adapterLimits, now);

        RoutingDecision decision = new RoutingDecision(UUID.randomUUID(), fileId, active.label(),
                json.valueToTree(classification),
                features(policy, category, file.getSizeBytes(), states),
                json.valueToTree(outcome.candidates()),
                outcome.selected(), now);
        if (outcome.fallbackUsed()) {
            decision.setFallback(outcome.fallbackReason());
        }
        decision.setExplanationRo(outcome.explanationRo());
        decisions.save(decision);

        file.setCategory(category);
        if (file.getContentType() == null) {
            file.setContentType(classification.detectedMimeType());
        }
        ObjectNode details = json.createObjectNode()
                .put("decisionId", decision.getId().toString())
                .put("policyVersion", active.label());
        if (outcome.selected() != null) {
            file.setStatus(FileStatus.DESTINATION_SELECTED);
            file.setStorageLocation(outcome.selected(),
                    StorageKeys.forCategory(category, file.getReceivedAt(), file.getId()));
            details.put("selectedStorage", outcome.selected().name())
                    .put("fallbackUsed", outcome.fallbackUsed());
            if (outcome.fallbackUsed()) {
                details.put("fallbackReason", outcome.fallbackReason());
            }
            events.save(new FileEvent(fileId, FileEventType.ROUTED, AGENT_ACTOR, details, file.getTraceId(), now));
        } else {
            file.setStatus(FileStatus.PENDING);
            details.put("status", FileStatus.PENDING.name()).put("reason", outcome.pendingReason());
            events.save(new FileEvent(fileId, FileEventType.STATUS_CHANGED, AGENT_ACTOR, details,
                    file.getTraceId(), now));
        }
        files.save(file);
        log.info("File {} ({}, {} bytes) routed with policy {}: {}", fileId, category, file.getSizeBytes(),
                active.label(), outcome.selected() == null ? "PENDING" : outcome.selected());
        return decision;
    }

    /** The values the decision was based on, exactly as they were at that moment. */
    private ObjectNode features(Policy policy, FileCategory category, long sizeBytes,
                                Map<StorageType, StorageState> states) {
        CategoryPolicy rules = policy.category(category);
        ObjectNode features = json.createObjectNode();
        features.put("category", category.name());
        features.put("sizeBytes", sizeBytes);
        features.put("sizeText", Sizes.format(sizeBytes));
        features.set("requirements", json.valueToTree(Map.of(
                "needsResumable", rules.constraints().needsResumable(),
                "needsEncryption", rules.constraints().needsEncryption(),
                "needsImmediateRead", rules.constraints().needsImmediateRead(),
                "idempotent", rules.constraints().idempotent())));
        features.set("weights", json.valueToTree(rules.weights()));
        ObjectNode storages = features.putObject("storages");
        for (StorageType storage : StorageType.values()) {
            StorageState state = states.getOrDefault(storage, StorageState.unknown());
            ObjectNode node = storages.putObject(storage.name());
            node.put("status", state.status().name());
            node.put("lastCheck", state.lastCheck() == null ? null : state.lastCheck().toString());
            node.put("latencyMs", state.latencyMs());
            node.put("latencySource", state.latencySource());
            node.put("effectiveMaxSizeBytes", RoutingEngine.effectiveLimit(policy, rules, storage, adapterLimits));
        }
        return features;
    }
}
