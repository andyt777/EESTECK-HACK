package ro.filegrid.storage;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.regex.Pattern;
import ro.filegrid.file.FileCategory;

/**
 * Storage key layout, identical on every storage:
 * {@code <segment>/<yyyy>/<MM>/<dd>/<uuid>}.
 *
 * <p>The first segment selects the S3 bucket and the top-level FTP directory.
 * The object name is a UUID, so no client name or other personal data ever
 * appears in a key or path.
 */
public final class StorageKeys {

    /** Segment used by benchmark runs (Stage 17); kept apart from real data. */
    public static final String BENCHMARK_SEGMENT = "benchmark";

    private static final DateTimeFormatter DATE_PATH =
            DateTimeFormatter.ofPattern("yyyy/MM/dd").withZone(ZoneOffset.UTC);
    private static final Pattern VALID_KEY = Pattern.compile("[a-z]+(/[A-Za-z0-9][A-Za-z0-9._-]*)+");
    private static final int MAX_LENGTH = 512;

    private StorageKeys() {
    }

    public static String forCategory(FileCategory category, Instant receivedAt, UUID objectId) {
        return build(category.storageSegment(), receivedAt, objectId);
    }

    public static String forBenchmark(Instant at, UUID objectId) {
        return build(BENCHMARK_SEGMENT, at, objectId);
    }

    /** Returns the first segment of a key (the part that selects bucket / directory). */
    public static String segmentOf(String key) {
        return requireValid(key).substring(0, key.indexOf('/'));
    }

    /** Returns the key without its first segment. */
    public static String pathWithinSegment(String key) {
        return requireValid(key).substring(key.indexOf('/') + 1);
    }

    /**
     * Rejects anything that is not a plain relative key. This is what prevents
     * path traversal on the FTP storage.
     */
    public static String requireValid(String key) {
        if (key == null || key.length() > MAX_LENGTH || !VALID_KEY.matcher(key).matches() || key.contains("..")) {
            throw new IllegalArgumentException("Invalid storage key: " + key);
        }
        return key;
    }

    private static String build(String segment, Instant at, UUID objectId) {
        return segment + "/" + DATE_PATH.format(at) + "/" + objectId;
    }
}
