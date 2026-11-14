package ro.filegrid.passport;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background integrity check: every stored file that has not been verified for
 * a while, plus a random sample of the others, is read back and re-hashed.
 * Recent meter readings are sampled too.
 */
@Component
@Profile("api")
public class IntegrityScrubber {

    private static final Logger log = LoggerFactory.getLogger(IntegrityScrubber.class);
    static final String ACTOR = "integrity-scrubber";

    private final JdbcTemplate metaDb;
    private final FileContentService content;
    private final int sampleSize;
    private final Duration maxAge;
    private final long maxBytes;

    public IntegrityScrubber(JdbcTemplate metaDb, FileContentService content,
                             @Value("${filegrid.integrity.scrub-sample-size:10}") int sampleSize,
                             @Value("${filegrid.integrity.scrub-max-age:7d}") Duration maxAge,
                             @Value("${filegrid.integrity.scrub-max-bytes:1073741824}") long maxBytes) {
        this.metaDb = metaDb;
        this.content = content;
        this.sampleSize = sampleSize;
        this.maxAge = maxAge;
        this.maxBytes = maxBytes;
    }

    @Scheduled(initialDelayString = "${filegrid.integrity.scrub-interval:15m}",
            fixedDelayString = "${filegrid.integrity.scrub-interval:15m}")
    public void scrub() {
        if (sampleSize <= 0) {
            return;
        }
        Set<UUID> ids = new LinkedHashSet<>(metaDb.queryForList("""
                SELECT id FROM file_record
                 WHERE status = 'STORED_VERIFIED' AND category <> 'METER_READING' AND integrity_status <> 'MISMATCH'
                   AND size_bytes <= ? AND (last_verified_at IS NULL OR last_verified_at < ?)
                 ORDER BY last_verified_at NULLS FIRST LIMIT ?
                """, UUID.class, maxBytes, Timestamp.from(Instant.now().minus(maxAge)), sampleSize * 5));
        ids.addAll(metaDb.queryForList("""
                SELECT id FROM file_record
                 WHERE status = 'STORED_VERIFIED' AND category <> 'METER_READING' AND integrity_status <> 'MISMATCH'
                   AND size_bytes <= ?
                 ORDER BY random() LIMIT ?
                """, UUID.class, maxBytes, sampleSize));
        ids.addAll(metaDb.queryForList("""
                SELECT id FROM file_record
                 WHERE category = 'METER_READING' AND received_at > now() - interval '10 minutes'
                   AND integrity_status <> 'MISMATCH'
                 ORDER BY received_at DESC LIMIT ?
                """, UUID.class, sampleSize));
        int mismatches = 0;
        for (UUID id : ids) {
            try {
                if (!content.verify(id, ACTOR, "SCRUBBER").intact()) {
                    mismatches++;
                }
            } catch (RuntimeException e) {
                log.debug("Scrubber skipped {}: {}", id, e.getMessage());
            }
        }
        if (!ids.isEmpty()) {
            log.info("Integrity scrubber: {} file(s) checked, {} mismatch(es)", ids.size(), mismatches);
        }
    }
}
