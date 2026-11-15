package ro.filegrid.passport;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ro.filegrid.events.FileLifecycle;
import ro.filegrid.file.DataOrigin;
import ro.filegrid.file.FileCategory;
import ro.filegrid.file.FileContext;
import ro.filegrid.file.FileContextRepository;
import ro.filegrid.file.FileEvent;
import ro.filegrid.file.FileEventRepository;
import ro.filegrid.file.FileEventType;
import ro.filegrid.file.FileRecord;
import ro.filegrid.file.FileStatus;
import ro.filegrid.file.IntegrityStatus;
import ro.filegrid.file.RoutingDecision;
import ro.filegrid.file.RoutingDecisionRepository;
import ro.filegrid.file.Sensitivity;
import ro.filegrid.security.AccessPolicy;
import ro.filegrid.security.CurrentActorProvider;
import ro.filegrid.storage.StorageType;
import ro.filegrid.text.Texts;

/**
 * Finding files and reading their passport: {@code GET /api/files},
 * {@code GET /api/files/{id}/passport}, {@code GET /api/categories/summary},
 * {@code GET /api/decisions}.
 */
@RestController
@Profile("api")
public class FileQueryController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(FileQueryController.class);

    private static final int MAX_PAGE_SIZE = 200;
    private static final Duration ACCESS_AUDIT_WINDOW = Duration.ofMinutes(1);

    private static final String FILE_COLUMNS = """
            f.id, f.original_name, f.category, f.content_type, f.size_bytes, f.received_at, f.stored_at, f.status,
            f.integrity_status, f.storage_type, f.data_origin, f.source, f.sensitivity, f.encrypted, f.uploaded_by,
            c.zone, c.station, c.equipment, c.meter_id, c.event_type, c.event_time
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final FileContentService content;
    private final FileContextRepository contexts;
    private final FileEventRepository events;
    private final RoutingDecisionRepository decisions;
    private final FileLifecycle lifecycle;
    private final CurrentActorProvider actors;
    private final AccessPolicy access;
    private final Texts texts;

    private volatile List<CategorySummary> cachedSummary;

    public FileQueryController(NamedParameterJdbcTemplate jdbc, FileContentService content,
                               FileContextRepository contexts, FileEventRepository events,
                               RoutingDecisionRepository decisions, FileLifecycle lifecycle,
                               CurrentActorProvider actors, AccessPolicy access, Texts texts) {
        this.jdbc = jdbc;
        this.content = content;
        this.contexts = contexts;
        this.events = events;
        this.decisions = decisions;
        this.lifecycle = lifecycle;
        this.actors = actors;
        this.access = access;
        this.texts = texts;
    }

    // ------------------------------------------------------------------ search

    public record FileSummary(UUID id, String name, FileCategory category, String contentType, long sizeBytes,
                              Instant receivedAt, Instant storedAt, FileStatus status,
                              IntegrityStatus integrityStatus, StorageType storage, DataOrigin dataOrigin,
                              String source, Sensitivity sensitivity, boolean encrypted, String uploadedBy,
                              String zone, String station, String equipment, String meterId, String eventType,
                              Instant eventTime) {
    }

    public record Page<T>(List<T> items, int page, int size, long total) {
    }

    @GetMapping("/files")
    public Page<FileSummary> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) FileCategory category,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) Long sizeMin,
            @RequestParam(required = false) Long sizeMax,
            @RequestParam(required = false) String zone,
            @RequestParam(required = false) String station,
            @RequestParam(required = false) String contentType,
            @RequestParam(required = false) StorageType storage,
            @RequestParam(required = false) FileStatus status,
            @RequestParam(required = false) IntegrityStatus integrity,
            @RequestParam(required = false) DataOrigin dataOrigin,
            @RequestParam(defaultValue = "false") boolean excludeMeters,
            @RequestParam(defaultValue = "receivedAt") String sort,
            @RequestParam(defaultValue = "desc") String dir,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        String actor = require(FileContentController.PERMISSION_READ);
        boolean pii = access.isAllowed(actor, FileContentController.PERMISSION_PII);
        int pageSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        int pageIndex = Math.max(0, page);
        MapSqlParameterSource params = new MapSqlParameterSource();
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (q != null && !q.isBlank()) {
            String term = q.trim();
            params.addValue("q", "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
            // Without pii:read the name and description of personal documents are not searchable either.
            String personalText = pii ? "(f.original_name ILIKE :q OR c.description ILIKE :q)"
                    : "(f.sensitivity <> 'PERSONAL_DATA' AND (f.original_name ILIKE :q OR c.description ILIKE :q))";
            where.append(" AND (" + personalText + " OR c.station ILIKE :q"
                    + " OR c.zone ILIKE :q OR c.equipment ILIKE :q OR c.meter_id ILIKE :q");
            try {
                params.addValue("qId", UUID.fromString(term));
                where.append(" OR f.id = :qId");
            } catch (IllegalArgumentException notAnId) {
                // free text only
            }
            where.append(")");
        }
        if (category != null) {
            where.append(" AND f.category = :category");
            params.addValue("category", category.name());
        } else if (excludeMeters) {
            where.append(" AND f.category <> 'METER_READING'");
        }
        if (from != null) {
            where.append(" AND f.received_at >= :from");
            params.addValue("from", Timestamp.from(from));
        }
        if (to != null) {
            where.append(" AND f.received_at <= :to");
            params.addValue("to", Timestamp.from(to));
        }
        if (sizeMin != null) {
            where.append(" AND f.size_bytes >= :sizeMin");
            params.addValue("sizeMin", sizeMin);
        }
        if (sizeMax != null) {
            where.append(" AND f.size_bytes <= :sizeMax");
            params.addValue("sizeMax", sizeMax);
        }
        if (zone != null && !zone.isBlank()) {
            where.append(" AND c.zone ILIKE :zone");
            params.addValue("zone", zone.trim());
        }
        if (station != null && !station.isBlank()) {
            where.append(" AND c.station ILIKE :station");
            params.addValue("station", station.trim());
        }
        if (contentType != null && !contentType.isBlank()) {
            where.append(" AND f.content_type ILIKE :contentType");
            params.addValue("contentType", contentType.trim() + "%");
        }
        if (storage != null) {
            where.append(" AND f.storage_type = :storage");
            params.addValue("storage", storage.name());
        }
        if (status != null) {
            where.append(" AND f.status = :status");
            params.addValue("status", status.name());
        }
        if (integrity != null) {
            where.append(" AND f.integrity_status = :integrity");
            params.addValue("integrity", integrity.name());
        }
        if (dataOrigin != null) {
            where.append(" AND f.data_origin = :dataOrigin");
            params.addValue("dataOrigin", dataOrigin.name());
        }
        String from_ = " FROM file_record f LEFT JOIN file_context c ON c.file_id = f.id";
        String orderColumn = switch (sort.toLowerCase(Locale.ROOT)) {
            case "size" -> "f.size_bytes";
            case "name" -> "f.original_name";
            default -> "f.received_at";
        };
        String direction = "asc".equalsIgnoreCase(dir) ? "ASC" : "DESC";
        params.addValue("limit", pageSize).addValue("offset", (long) pageIndex * pageSize);
        List<FileSummary> items = jdbc.query("SELECT " + FILE_COLUMNS + from_ + where + " ORDER BY " + orderColumn
                + " " + direction + ", f.id LIMIT :limit OFFSET :offset", params, FileQueryController::summary);
        if (!pii) {
            items = items.stream().map(this::masked).toList();
        }
        Long total = jdbc.queryForObject("SELECT count(*)" + from_ + where, params, Long.class);
        return new Page<>(items, pageIndex, pageSize, total == null ? 0 : total);
    }

    /** The name of a personal document, as shown to someone without {@code pii:read}. */
    private FileSummary masked(FileSummary s) {
        if (s.sensitivity() != Sensitivity.PERSONAL_DATA) {
            return s;
        }
        return new FileSummary(s.id(), texts.get("file.maskedName"), s.category(), s.contentType(), s.sizeBytes(),
                s.receivedAt(), s.storedAt(), s.status(), s.integrityStatus(), s.storage(), s.dataOrigin(),
                s.source(), s.sensitivity(), s.encrypted(), s.uploadedBy(), s.zone(), s.station(), s.equipment(),
                s.meterId(), s.eventType(), s.eventTime());
    }

    private static FileSummary summary(ResultSet rs, int row) throws SQLException {
        String storage = rs.getString("storage_type");
        return new FileSummary(rs.getObject("id", UUID.class), rs.getString("original_name"),
                FileCategory.valueOf(rs.getString("category")), rs.getString("content_type"),
                rs.getLong("size_bytes"), instant(rs, "received_at"), instant(rs, "stored_at"),
                FileStatus.valueOf(rs.getString("status")), IntegrityStatus.valueOf(rs.getString("integrity_status")),
                storage == null ? null : StorageType.valueOf(storage), DataOrigin.valueOf(rs.getString("data_origin")),
                rs.getString("source"), Sensitivity.valueOf(rs.getString("sensitivity")), rs.getBoolean("encrypted"),
                rs.getString("uploaded_by"), rs.getString("zone"), rs.getString("station"), rs.getString("equipment"),
                rs.getString("meter_id"), rs.getString("event_type"), instant(rs, "event_time"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    // -------------------------------------------------------- category summary

    public record CategorySummary(FileCategory category, String label, long fileCount, long totalBytes,
                                  Instant lastReceivedAt, long pending, long mismatch, long transferFailed,
                                  long inProgress, Map<String, Long> byStorage, List<FileSummary> recent) {
    }

    @GetMapping("/categories/summary")
    public List<CategorySummary> categories() {
        require(FileContentController.PERMISSION_READ);
        // Served from the background refresh below: the aggregate scans every file_record (over a million rows
        // with the simulator running) and took longer than the UI waits. Computed inline only before the first refresh.
        List<CategorySummary> cached = cachedSummary;
        return cached != null ? cached : computeSummary();
    }

    /** Keeps the Village counters fresh without making any request wait for the full-table aggregate. */
    @Scheduled(initialDelayString = "2s", fixedDelayString = "${filegrid.summary.refresh-interval:10s}")
    void refreshSummary() {
        try {
            computeSummary();
        } catch (RuntimeException e) {
            log.warn("Category summary refresh failed (the previous one stays served): {}", e.getMessage());
        }
    }

    private List<CategorySummary> computeSummary() {
        MapSqlParameterSource none = new MapSqlParameterSource();
        Map<String, long[]> totals = new LinkedHashMap<>();
        Map<String, Instant> last = new LinkedHashMap<>();
        Map<String, Map<String, Long>> byStorage = new LinkedHashMap<>();
        jdbc.query("""
                SELECT category, storage_type, count(*) AS files, coalesce(sum(size_bytes), 0) AS bytes,
                       max(received_at) AS last_at,
                       count(*) FILTER (WHERE status = 'PENDING') AS pending,
                       count(*) FILTER (WHERE integrity_status = 'MISMATCH') AS mismatch,
                       count(*) FILTER (WHERE status = 'TRANSFER_FAILED') AS failed,
                       count(*) FILTER (WHERE status NOT IN ('STORED_VERIFIED', 'PENDING', 'TRANSFER_FAILED'))
                           AS in_progress
                  FROM file_record GROUP BY category, storage_type
                """, none, rs -> {
            String category = rs.getString("category");
            long[] sum = totals.computeIfAbsent(category, k -> new long[6]);
            sum[0] += rs.getLong("files");
            sum[1] += rs.getLong("bytes");
            sum[2] += rs.getLong("pending");
            sum[3] += rs.getLong("mismatch");
            sum[4] += rs.getLong("failed");
            sum[5] += rs.getLong("in_progress");
            Instant at = instant(rs, "last_at");
            if (at != null) {
                last.merge(category, at, (a, b) -> a.isAfter(b) ? a : b);
            }
            String storage = rs.getString("storage_type");
            if (storage != null) {
                byStorage.computeIfAbsent(category, k -> new LinkedHashMap<>()).put(storage, rs.getLong("files"));
            }
        });
        List<CategorySummary> result = new ArrayList<>();
        for (FileCategory category : FileCategory.values()) {
            long[] sum = totals.getOrDefault(category.name(), new long[6]);
            List<FileSummary> recent = jdbc.query("SELECT " + FILE_COLUMNS + """
                     FROM file_record f LEFT JOIN file_context c ON c.file_id = f.id
                    WHERE f.category = :category ORDER BY f.received_at DESC LIMIT 5
                    """, new MapSqlParameterSource("category", category.name()), FileQueryController::summary);
            result.add(new CategorySummary(category, texts.get("category." + category.name()), sum[0], sum[1],
                    last.get(category.name()), sum[2], sum[3], sum[4], sum[5],
                    byStorage.getOrDefault(category.name(), Map.of()), recent));
        }
        cachedSummary = result;
        return result;
    }

    // ---------------------------------------------------------------- passport

    public record Identity(UUID id, String name, FileCategory category, String categoryLabel, String contentType,
                           long sizeBytes, Instant receivedAt, String source, String uploadedBy,
                           DataOrigin dataOrigin, Sensitivity sensitivity, boolean encrypted, String traceId) {
    }

    /** @param done whether the step has happened; {@code at} is when (if known) */
    public record JourneyStep(int step, String key, String label, boolean done, Instant at) {
    }

    public record StorageView(StorageType type, String label, Instant storedAt) {
    }

    public record DecisionView(String policyVersion, JsonNode detection, JsonNode features, JsonNode candidates,
                               StorageType selectedStorage, boolean fallbackUsed, String fallbackReason,
                               String explanation, Instant decidedAt) {
    }

    public record IntegrityView(IntegrityStatus status, String label, String sha256, String sha256Stored,
                                Instant lastVerifiedAt) {
    }

    public record AuditEntry(long id, FileEventType type, String label, String actor, JsonNode details, Instant at) {
    }

    public record Passport(Identity identity, FileStatus status, String statusLabel, Map<String, Object> context,
                           List<JourneyStep> journey, StorageView storage, DecisionView decision,
                           IntegrityView integrity, Page<AuditEntry> audit, List<String> actions) {
    }

    @GetMapping("/files/{id}/passport")
    public Passport passport(@PathVariable UUID id,
                             @RequestParam(defaultValue = "0") int auditPage,
                             @RequestParam(defaultValue = "50") int auditSize,
                             @RequestParam(required = false) FileEventType auditType) {
        String actor = require(FileContentController.PERMISSION_READ);
        FileRecord file = content.require(id);
        List<FileEvent> all = events.findByFileIdOrderByAtAsc(id);

        // Opening the passport is an access; repeated refreshes by the same person are recorded once a minute.
        Instant now = Instant.now();
        boolean recentlyRecorded = all.stream().anyMatch(e -> e.getType() == FileEventType.ACCESSED
                && actor.equals(e.getActor()) && e.getAt().isAfter(now.minus(ACCESS_AUDIT_WINDOW))
                && "passport".equals(e.getDetails().path("what").asText()));
        if (!recentlyRecorded) {
            lifecycle.audit(file, FileEventType.ACCESSED, actor, lifecycle.details().put("what", "passport"));
            all = events.findByFileIdOrderByAtAsc(id);
        }

        boolean hidePersonal = file.getSensitivity() == Sensitivity.PERSONAL_DATA
                && !access.isAllowed(actor, FileContentController.PERMISSION_PII);
        Identity identity = new Identity(file.getId(),
                hidePersonal ? texts.get("file.maskedName") : file.getOriginalName(), file.getCategory(),
                texts.get("category." + file.getCategory().name()), file.getContentType(), file.getSizeBytes(),
                file.getReceivedAt(), file.getSource().name(), file.getUploadedBy(), file.getDataOrigin(),
                file.getSensitivity(), file.isEncrypted(), file.getTraceId());

        Map<String, Object> context = new LinkedHashMap<>();
        contexts.findById(id).ifPresent(c -> fillContext(context, c));
        if (hidePersonal) {
            context.remove("description"); // free text, may contain personal data
        }

        DecisionView decision = decisions.findFirstByFileIdOrderByDecidedAtDesc(id)
                .map(FileQueryController::decisionView).orElse(null);
        StorageView storage = file.getStorageType() == null ? null : new StorageView(file.getStorageType(),
                lifecycle.storageName(file.getStorageType()), file.getStoredAt());
        IntegrityView integrity = new IntegrityView(file.getIntegrityStatus(),
                texts.get("integrity.status." + file.getIntegrityStatus().name()), file.getSha256Plain(),
                file.getSha256Stored(), file.getLastVerifiedAt());

        List<AuditEntry> entries = all.stream()
                .filter(e -> auditType == null || e.getType() == auditType)
                .map(e -> new AuditEntry(e.getId(), e.getType(), texts.get("event." + e.getType().name()),
                        e.getActor(), hidePersonal ? withoutName(e.getDetails()) : e.getDetails(), e.getAt()))
                .toList();
        int size = Math.max(1, Math.min(auditSize, MAX_PAGE_SIZE));
        int pageIndex = Math.max(0, auditPage);
        int fromIndex = Math.min(entries.size(), pageIndex * size);
        Page<AuditEntry> audit = new Page<>(entries.subList(fromIndex, Math.min(entries.size(), fromIndex + size)),
                pageIndex, size, entries.size());

        return new Passport(identity, file.getStatus(), texts.get("status." + file.getStatus().name()), context,
                journey(file, all, decision), storage, decision, integrity, audit, actions(file, actor));
    }

    private static JsonNode withoutName(JsonNode details) {
        if (details instanceof com.fasterxml.jackson.databind.node.ObjectNode object && object.has("originalName")) {
            com.fasterxml.jackson.databind.node.ObjectNode copy = object.deepCopy();
            copy.remove("originalName");
            return copy;
        }
        return details;
    }

    private static void fillContext(Map<String, Object> context, FileContext c) {
        put(context, "zone", c.getZone());
        put(context, "station", c.getStation());
        put(context, "equipment", c.getEquipment());
        put(context, "meterId", c.getMeterId());
        put(context, "eventType", c.getEventType());
        put(context, "eventTime", c.getEventTime());
        put(context, "description", c.getDescription());
    }

    private static void put(Map<String, Object> context, String key, Object value) {
        if (value != null && !(value instanceof String text && text.isBlank())) {
            context.put(key, value);
        }
    }

    private static DecisionView decisionView(RoutingDecision d) {
        return new DecisionView(d.getPolicyVersion(), d.getDetection(), d.getFeatures(), d.getCandidates(),
                d.getSelectedStorage(), d.isFallbackUsed(), d.getFallbackReason(), d.getExplanationRo(),
                d.getDecidedAt());
    }

    /** The seven steps of §6.3, each backed by an audit entry (or by the record itself for batch readings). */
    private List<JourneyStep> journey(FileRecord file, List<FileEvent> all, DecisionView decision) {
        boolean stored = file.getStatus() == FileStatus.STORED_VERIFIED;
        Instant received = first(all, FileEventType.RECEIVED, null);
        Instant analyzed = first(all, FileEventType.STATUS_CHANGED, FileStatus.ANALYZING.name());
        Instant classified = first(all, FileEventType.CLASSIFIED, null);
        Instant routed = first(all, FileEventType.ROUTED, null);
        Instant transferring = first(all, FileEventType.TRANSFER_STARTED, null);
        Instant storedAt = last(all, FileEventType.STORED);
        Instant verified = last(all, FileEventType.VERIFIED);
        if (received == null) {
            received = file.getReceivedAt();
        }
        if (stored) {
            // Readings ingested in batches skip the per-file pipeline: one transaction does all the steps.
            Instant fallback = file.getStoredAt();
            analyzed = analyzed != null ? analyzed : received;
            classified = classified != null ? classified : received;
            routed = routed != null ? routed : decision != null ? decision.decidedAt() : received;
            transferring = transferring != null ? transferring : fallback;
            storedAt = storedAt != null ? storedAt : fallback;
            verified = verified != null ? verified : fallback;
        }
        Instant[] at = {received, analyzed, classified, routed, transferring, storedAt, verified};
        String[] keys = {"RECEIVED", "ANALYZED", "CATEGORIZED", "EVALUATED", "SENT", "STORED", "VERIFIED"};
        List<JourneyStep> steps = new ArrayList<>();
        for (int i = 0; i < keys.length; i++) {
            steps.add(new JourneyStep(i + 1, keys[i], texts.get("journey." + keys[i]), at[i] != null, at[i]));
        }
        return steps;
    }

    private static Instant first(List<FileEvent> all, FileEventType type, String status) {
        return all.stream().filter(e -> e.getType() == type
                        && (status == null || status.equals(e.getDetails().path("status").asText())))
                .map(FileEvent::getAt).findFirst().orElse(null);
    }

    private static Instant last(List<FileEvent> all, FileEventType type) {
        return all.stream().filter(e -> e.getType() == type).map(FileEvent::getAt)
                .reduce((a, b) -> b).orElse(null);
    }

    /** What this person may do with this file right now (§6.6); the UI shows only these. */
    private List<String> actions(FileRecord file, String actor) {
        List<String> actions = new ArrayList<>(List.of("COPY_ID", "VIEW_HISTORY", "BACK_TO_CATEGORY"));
        boolean stored = file.getStatus() == FileStatus.STORED_VERIFIED;
        boolean mayRead = file.getSensitivity() != Sensitivity.PERSONAL_DATA
                || access.isAllowed(actor, FileContentController.PERMISSION_PII);
        if (!stored) {
            return actions;
        }
        if (access.isAllowed(actor, FileContentController.PERMISSION_VERIFY)) {
            actions.add("VERIFY");
        }
        if (!mayRead) {
            return actions;
        }
        if (file.getIntegrityStatus() == IntegrityStatus.MISMATCH) {
            if (access.isAllowed(actor, FileContentController.PERMISSION_SUSPECT)) {
                actions.add("DOWNLOAD_SUSPECT");
            }
            return actions;
        }
        actions.add("DOWNLOAD");
        String type = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        if (type.startsWith("image/") || type.startsWith("video/") || type.startsWith("text/")
                || type.equals("application/pdf") || type.equals("application/json")) {
            actions.add("PREVIEW");
        }
        return actions;
    }

    // --------------------------------------------------------------- decisions

    public record DecisionSummary(UUID id, UUID fileId, String fileName, FileCategory category,
                                  FileStatus fileStatus, long sizeBytes, String policyVersion,
                                  StorageType selectedStorage, boolean fallbackUsed, String fallbackReason,
                                  String explanation, Instant decidedAt) {
    }

    /** Recent routing decisions of Agent 01 (Decision Explorer). Batch meter readings have no per-file decision. */
    @GetMapping("/decisions")
    public Page<DecisionSummary> decisions(@RequestParam(required = false) FileCategory category,
                                           @RequestParam(required = false) StorageType storage,
                                           @RequestParam(required = false) Boolean fallback,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "25") int size) {
        String actor = require(FileContentController.PERMISSION_READ);
        boolean pii = access.isAllowed(actor, FileContentController.PERMISSION_PII);
        String masked = texts.get("file.maskedName");
        int pageSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        int pageIndex = Math.max(0, page);
        MapSqlParameterSource params = new MapSqlParameterSource();
        StringBuilder where = new StringBuilder("""
                 FROM routing_decision d JOIN file_record f ON f.id = d.file_id
                WHERE d.decided_at = (SELECT max(x.decided_at) FROM routing_decision x WHERE x.file_id = d.file_id)
                """);
        if (category != null) {
            where.append(" AND f.category = :category");
            params.addValue("category", category.name());
        }
        if (storage != null) {
            where.append(" AND d.selected_storage = :storage");
            params.addValue("storage", storage.name());
        }
        if (fallback != null) {
            where.append(" AND d.fallback_used = :fallback");
            params.addValue("fallback", fallback);
        }
        params.addValue("limit", pageSize).addValue("offset", (long) pageIndex * pageSize);
        List<DecisionSummary> items = jdbc.query("""
                SELECT d.id, d.file_id, f.original_name, f.sensitivity, f.category, f.status, f.size_bytes,
                       d.policy_version, d.selected_storage, d.fallback_used, d.fallback_reason, d.explanation_ro,
                       d.decided_at
                """ + where + " ORDER BY d.decided_at DESC LIMIT :limit OFFSET :offset", params, (rs, row) -> {
            String selected = rs.getString("selected_storage");
            boolean personal = Sensitivity.PERSONAL_DATA.name().equals(rs.getString("sensitivity"));
            return new DecisionSummary(rs.getObject("id", UUID.class), rs.getObject("file_id", UUID.class),
                    personal && !pii ? masked : rs.getString("original_name"),
                    FileCategory.valueOf(rs.getString("category")),
                    FileStatus.valueOf(rs.getString("status")), rs.getLong("size_bytes"),
                    rs.getString("policy_version"), selected == null ? null : StorageType.valueOf(selected),
                    rs.getBoolean("fallback_used"), rs.getString("fallback_reason"), rs.getString("explanation_ro"),
                    instant(rs, "decided_at"));
        });
        Long total = jdbc.queryForObject("SELECT count(*)" + where, params, Long.class);
        return new Page<>(items, pageIndex, pageSize, total == null ? 0 : total);
    }

    private String require(String permission) {
        String actor = actors.currentActor();
        if (!access.isAllowed(actor, permission)) {
            throw new ProblemException(HttpStatus.FORBIDDEN, "forbidden", texts.get("file.error.forbidden"));
        }
        return actor;
    }
}
