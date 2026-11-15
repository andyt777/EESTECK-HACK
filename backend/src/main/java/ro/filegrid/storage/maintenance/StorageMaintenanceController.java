package ro.filegrid.storage.maintenance;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ro.filegrid.security.Authorizer;
import ro.filegrid.security.RolePermissions;
import ro.filegrid.storage.StorageType;

/**
 * Planned maintenance of a storage. While it is on, Agent 01 does not route new files to that storage and says
 * so in every decision ("destinația este în mentenanță planificată"); the files fall back on the policy chain.
 * Only {@code policy:write} (admin) may change it: it is routing configuration, like the policy itself. The row
 * keeps who started it and when; every change is also logged with the actor.
 */
@RestController
@Profile("api")
public class StorageMaintenanceController {

    private static final Logger log = LoggerFactory.getLogger(StorageMaintenanceController.class);
    static final int MAX_REASON = 200;

    public record Maintenance(String storage, String reason, String startedBy, Instant startedAt) {
    }

    public record Request(String reason) {
    }

    private final JdbcTemplate metaDb;
    private final Authorizer authorizer;

    public StorageMaintenanceController(JdbcTemplate metaDb, Authorizer authorizer) {
        this.metaDb = metaDb;
        this.authorizer = authorizer;
    }

    @GetMapping("/storage/maintenance")
    public List<Maintenance> active() {
        authorizer.require(RolePermissions.STORAGE_READ);
        return metaDb.query("SELECT storage, reason, started_by, started_at FROM storage_maintenance ORDER BY storage",
                (rs, i) -> {
                    Timestamp at = rs.getTimestamp("started_at");
                    return new Maintenance(rs.getString("storage"), rs.getString("reason"),
                            rs.getString("started_by"), at == null ? null : at.toInstant());
                });
    }

    @PutMapping("/admin/storage/{storage}/maintenance")
    public Maintenance start(@PathVariable String storage, @RequestBody(required = false) Request request) {
        String actor = authorizer.require(RolePermissions.POLICY_WRITE);
        StorageType type = parse(storage);
        String reason = request == null || request.reason() == null ? "" : request.reason().strip();
        if (reason.isEmpty()) {
            reason = "fără motiv precizat";
        }
        if (reason.length() > MAX_REASON) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "reason longer than " + MAX_REASON);
        }
        metaDb.update("""
                INSERT INTO storage_maintenance (storage, reason, started_by) VALUES (?, ?, ?)
                ON CONFLICT (storage) DO UPDATE SET reason = EXCLUDED.reason, started_by = EXCLUDED.started_by,
                                                    started_at = now()
                """, type.name(), reason, actor);
        log.info("Storage {} put in maintenance by {}: {}", type, actor, reason);
        return active().stream().filter(m -> m.storage().equals(type.name())).findFirst().orElseThrow();
    }

    @DeleteMapping("/admin/storage/{storage}/maintenance")
    public void stop(@PathVariable String storage) {
        String actor = authorizer.require(RolePermissions.POLICY_WRITE);
        StorageType type = parse(storage);
        int removed = metaDb.update("DELETE FROM storage_maintenance WHERE storage = ?", type.name());
        if (removed > 0) {
            log.info("Storage {} back in routing (maintenance ended by {})", type, actor);
        }
    }

    private static StorageType parse(String storage) {
        try {
            return StorageType.valueOf(storage.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown storage " + storage);
        }
    }
}
