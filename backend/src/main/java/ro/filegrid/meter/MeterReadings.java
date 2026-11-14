package ro.filegrid.meter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import ro.filegrid.file.FileCategory;
import ro.filegrid.storage.Sha256;

/** Identity rules of meter readings, shared by the api and worker-meter. */
public final class MeterReadings {

    private static final DateTimeFormatter DATE_PATH =
            DateTimeFormatter.ofPattern("yyyy/MM/dd").withZone(ZoneOffset.UTC);

    private MeterReadings() {
    }

    /**
     * {@code readingId = SHA-256(meterId|periodStart)}, with periodStart in
     * ISO-8601 UTC ({@link Instant#toString()}), so the same period of the
     * same meter always gets the same id, whoever sends it and however often.
     */
    public static String readingId(String meterId, Instant periodStart) {
        MessageDigest digest = Sha256.newDigest();
        digest.update((meterId + "|" + periodStart.toString()).getBytes(StandardCharsets.UTF_8));
        return Sha256.hex(digest);
    }

    /** The file_record id of a reading: derived from readingId, so a duplicate collides on the key too. */
    public static UUID fileId(String readingId) {
        return UUID.nameUUIDFromBytes(("meter-reading:" + readingId).getBytes(StandardCharsets.UTF_8));
    }

    /** Storage key of a reading in the Database storage: {@code meters/yyyy/MM/dd/<readingId>}. */
    public static String storageKey(String readingId, Instant periodStart) {
        return FileCategory.METER_READING.storageSegment() + "/" + DATE_PATH.format(periodStart) + "/" + readingId;
    }

    public static String sha256(byte[] content) {
        MessageDigest digest = Sha256.newDigest();
        digest.update(content);
        return Sha256.hex(digest);
    }
}
