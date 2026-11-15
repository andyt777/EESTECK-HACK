package ro.filegrid.storage.health;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import ro.filegrid.security.Authorizer;
import ro.filegrid.security.RolePermissions;
import ro.filegrid.security.RolePermissions;
import ro.filegrid.text.Texts;

/** Latest probe result of each storage, as written by {@link StorageHealthMonitor}. */
@RestController
@Profile("api")
public class StorageHealthController {

    public record StorageHealthView(String storage, String label, String status, Instant lastCheck, long latencyMs,
                                    String detail) {
    }

    private final JdbcTemplate metaDb;
    private final Texts texts;
    private final Authorizer authorizer;

    public StorageHealthController(JdbcTemplate metaDb, Texts texts, Authorizer authorizer) {
        this.metaDb = metaDb;
        this.texts = texts;
        this.authorizer = authorizer;
    }

    @GetMapping("/storage/health")
    public List<StorageHealthView> health() {
        authorizer.require(RolePermissions.STORAGE_READ);
        return metaDb.query("SELECT storage, status, last_check, latency_ms, detail FROM storage_health ORDER BY storage",
                (rs, i) -> {
                    Timestamp t = rs.getTimestamp("last_check");
                    String storage = rs.getString("storage");
                    return new StorageHealthView(storage, texts.get("storage." + storage), rs.getString("status"),
                            t == null ? null : t.toInstant(), rs.getLong("latency_ms"), rs.getString("detail"));
                });
    }
}
