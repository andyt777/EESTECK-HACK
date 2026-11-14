package ro.filegrid.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import ro.filegrid.policy.PolicyParser.PolicySyntaxException;
import ro.filegrid.storage.Sha256;
import ro.filegrid.text.Texts;

/**
 * Owns the active routing policy.
 *
 * <p>Every accepted policy text is a row in {@code policy_version}; the active
 * policy is the row with the highest version, whether it came from
 * {@code config/policies.yaml} or from the admin API. A text that does not
 * parse or does not validate is refused and the active policy stays as it was.
 *
 * <p>Every instance (api and workers) re-reads the latest version on a fixed
 * interval, so a change made through one instance reaches all of them without
 * a restart.
 */
@Service
public class PolicyService {

    private static final Logger log = LoggerFactory.getLogger(PolicyService.class);
    private static final String COLUMNS = "version, source, author, content, content_sha256, created_at";

    private final JdbcTemplate metaDb;
    private final TransactionTemplate transaction;
    private final ObjectMapper json;
    private final Texts texts;
    private final PolicyValidator validator;

    private volatile ActivePolicy active;

    public PolicyService(JdbcTemplate metaDb, TransactionTemplate transaction, ObjectMapper json, Texts texts) {
        this.metaDb = metaDb;
        this.transaction = transaction;
        this.json = json;
        this.texts = texts;
        this.validator = new PolicyValidator(texts);
    }

    /**
     * The policy in force.
     *
     * @throws IllegalStateException when no valid policy has ever been loaded
     */
    public ActivePolicy active() {
        return activeIfPresent().orElseThrow(() -> new IllegalStateException(
                "No routing policy is available: config/policies.yaml was never loaded successfully"));
    }

    public Optional<ActivePolicy> activeIfPresent() {
        if (active == null) {
            refresh();
        }
        return Optional.ofNullable(active);
    }

    /**
     * Parses and validates a policy text without storing it.
     *
     * @throws InvalidPolicyException with every problem found
     */
    public Policy parseAndValidate(String yaml) {
        Policy policy;
        try {
            policy = PolicyParser.parse(yaml);
        } catch (PolicySyntaxException e) {
            throw new InvalidPolicyException(List.of(texts.get("policy.error.syntax", e.getMessage())));
        }
        List<String> errors = validator.validate(policy);
        if (!errors.isEmpty()) {
            throw new InvalidPolicyException(errors);
        }
        return policy;
    }

    /**
     * Validates the text and makes it the active policy, as a new version.
     * Submitting the text that is already active changes nothing.
     *
     * @throws InvalidPolicyException when the text is refused; the active policy is untouched
     */
    public SubmitResult submit(String yaml, PolicySource source, String author) {
        Policy policy = parseAndValidate(yaml);
        String sha256 = sha256(yaml);
        SubmitResult result = transaction.execute(status -> {
            // One writer at a time, so two submissions can never get the same version number.
            metaDb.execute("LOCK TABLE policy_version IN EXCLUSIVE MODE");
            Optional<StoredVersion> latest = latestStored();
            if (latest.isPresent() && latest.get().contentSha256().equals(sha256)) {
                StoredVersion same = latest.get();
                return new SubmitResult(new ActivePolicy(same.version(), same.source(), same.author(),
                        same.createdAt(), sha256, yaml, policy), false);
            }
            long version = latest.map(stored -> stored.version() + 1).orElse(1L);
            List<PolicyDiff.Change> diff = latest
                    .flatMap(stored -> tryParse(stored.content()))
                    .map(previous -> PolicyDiff.between(PolicyParser.toJson(previous), PolicyParser.toJson(policy)))
                    .orElse(List.of());
            Instant createdAt = Instant.now();
            metaDb.update("""
                            INSERT INTO policy_version (version, source, author, content, content_sha256, diff, created_at)
                            VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
                            """,
                    version, source.name(), author, yaml, sha256, json.valueToTree(diff).toString(),
                    java.sql.Timestamp.from(createdAt));
            return new SubmitResult(new ActivePolicy(version, source, author, createdAt, sha256, yaml, policy), true);
        });
        active = result.policy();
        if (result.changed()) {
            log.info("Routing policy {} is now active (source {}, author {})",
                    result.policy().label(), source, author);
        }
        return result;
    }

    /** Picks up a version stored by another instance. Keeps the current policy on any problem. */
    @Scheduled(fixedDelayString = "${filegrid.policy.check-interval}",
            initialDelayString = "${filegrid.policy.check-interval}")
    public void refresh() {
        try {
            Optional<StoredVersion> latest = latestStored();
            if (latest.isEmpty()) {
                return;
            }
            StoredVersion stored = latest.get();
            ActivePolicy current = active;
            if (current != null && current.version() == stored.version()) {
                return;
            }
            Policy policy = parseAndValidate(stored.content());
            active = new ActivePolicy(stored.version(), stored.source(), stored.author(), stored.createdAt(),
                    stored.contentSha256(), stored.content(), policy);
            log.info("Routing policy {} loaded from the database (source {})", active.label(), stored.source());
        } catch (InvalidPolicyException e) {
            log.error("The latest stored policy version is not valid and was ignored: {}", e.errors());
        } catch (RuntimeException e) {
            log.warn("Could not read the routing policy from the database: {}", e.getMessage());
        }
    }

    /** SHA-256 of the last text accepted from the policy file, if any. */
    public Optional<String> lastFileSha256() {
        return metaDb.query(
                "SELECT content_sha256 FROM policy_version WHERE source = 'FILE' ORDER BY version DESC LIMIT 1",
                (rs, row) -> rs.getString(1)).stream().findFirst();
    }

    /** Versions, newest first. */
    public List<VersionSummary> history(int page, int size) {
        return metaDb.query(
                "SELECT version, source, author, content_sha256, diff::text AS diff, created_at "
                        + "FROM policy_version ORDER BY version DESC LIMIT ? OFFSET ?",
                (rs, row) -> new VersionSummary(rs.getLong("version"), "v" + rs.getLong("version"),
                        PolicySource.valueOf(rs.getString("source")), rs.getString("author"),
                        rs.getTimestamp("created_at").toInstant(), rs.getString("content_sha256"),
                        readTree(rs.getString("diff"))),
                size, (long) page * size);
    }

    public long versionCount() {
        Long count = metaDb.queryForObject("SELECT count(*) FROM policy_version", Long.class);
        return count == null ? 0 : count;
    }

    public static String sha256(String text) {
        MessageDigest digest = Sha256.newDigest();
        digest.update(text.getBytes(StandardCharsets.UTF_8));
        return Sha256.hex(digest);
    }

    private Optional<StoredVersion> latestStored() {
        return metaDb.query("SELECT " + COLUMNS + " FROM policy_version ORDER BY version DESC LIMIT 1",
                PolicyService::mapStored).stream().findFirst();
    }

    private static StoredVersion mapStored(ResultSet rs, int row) throws SQLException {
        return new StoredVersion(rs.getLong("version"), PolicySource.valueOf(rs.getString("source")),
                rs.getString("author"), rs.getString("content"), rs.getString("content_sha256"),
                rs.getTimestamp("created_at").toInstant());
    }

    private Optional<Policy> tryParse(String yaml) {
        try {
            return Optional.of(PolicyParser.parse(yaml));
        } catch (PolicySyntaxException e) {
            return Optional.empty();
        }
    }

    private JsonNode readTree(String text) {
        try {
            return json.readTree(text);
        } catch (Exception e) {
            return json.createArrayNode();
        }
    }

    public enum PolicySource {
        /** Read from {@code config/policies.yaml}. */
        FILE,
        /** Submitted through the admin API. */
        ADMIN
    }

    /** A validated policy together with the version it was stored as. */
    public record ActivePolicy(long version, PolicySource source, String author, Instant createdAt,
                               String contentSha256, String content, Policy policy) {

        /** The name saved in {@code routing_decision.policy_version}, for example {@code v3}. */
        public String label() {
            return "v" + version;
        }
    }

    /**
     * @param changed {@code false} when the submitted text was already the active one
     */
    public record SubmitResult(ActivePolicy policy, boolean changed) {
    }

    public record VersionSummary(long version, String label, PolicySource source, String author,
                                 Instant createdAt, String contentSha256, JsonNode diff) {
    }

    private record StoredVersion(long version, PolicySource source, String author, String content,
                                 String contentSha256, Instant createdAt) {
    }

    /** The policy text was refused; {@link #errors()} lists every reason (in Romanian). */
    public static class InvalidPolicyException extends RuntimeException {

        private final transient List<String> errors;

        public InvalidPolicyException(List<String> errors) {
            super(String.join("; ", errors));
            this.errors = List.copyOf(errors);
        }

        public List<String> errors() {
            return errors;
        }
    }
}
