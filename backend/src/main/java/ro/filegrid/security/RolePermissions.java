package ro.filegrid.security;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Realm role → permission matrix of Stage 11 (docs/AUTH-DESIGN.md §3). The
 * only place where roles are translated into permissions; every decision goes
 * through {@link AccessPolicy}.
 */
public final class RolePermissions {

    public static final String FILE_READ = "file:read";
    public static final String FILE_WRITE = "file:write";
    public static final String FILE_VERIFY = "file:verify";
    public static final String FILE_READ_SUSPECT = "file:read-suspect";
    public static final String PII_READ = "pii:read";
    public static final String METERS_READ = "meters:read";
    public static final String METERS_WRITE = "meters:write";
    public static final String INCIDENT_READ = "incident:read";
    public static final String INCIDENT_CREATE = "incident:create";
    public static final String INCIDENT_WRITE = "incident:write";
    public static final String INCIDENT_CONFIRM = "incident:confirm";
    public static final String POLICY_READ = "policy:read";
    public static final String POLICY_WRITE = "policy:write";
    public static final String INTELLIGENCE_READ = "intelligence:read";
    public static final String INTELLIGENCE_WRITE = "intelligence:write";
    public static final String ASSISTANT_USE = "assistant:use";
    public static final String VOICE_USE = "voice:use";
    public static final String STORAGE_READ = "storage:read";
    public static final String ALERTS_INGEST = "alerts:ingest";

    public static final String ROLE_FIELD = "field";
    public static final String ROLE_DISPATCHER = "dispatcher";
    public static final String ROLE_CONNECTIONS = "connections";
    public static final String ROLE_ANALYST = "analyst";
    public static final String ROLE_INCIDENT_MANAGER = "incident-manager";
    public static final String ROLE_ADMIN = "admin";
    public static final String ROLE_SVC_METER_INGEST = "svc-meter-ingest";
    public static final String ROLE_SVC_ALERT_INGEST = "svc-alert-ingest";
    public static final String ROLE_SVC_INTELLIGENCE = "svc-intelligence";

    private static final Map<String, Set<String>> MATRIX = Map.of(
            ROLE_FIELD, Set.of(FILE_READ, FILE_WRITE, INCIDENT_READ, INCIDENT_CREATE, ASSISTANT_USE, VOICE_USE,
                    STORAGE_READ),
            ROLE_DISPATCHER, Set.of(FILE_READ, FILE_VERIFY, METERS_READ, INCIDENT_READ, INCIDENT_CREATE,
                    INCIDENT_WRITE, INCIDENT_CONFIRM, POLICY_READ, INTELLIGENCE_READ, ASSISTANT_USE, VOICE_USE,
                    STORAGE_READ),
            ROLE_CONNECTIONS, Set.of(FILE_READ, FILE_WRITE, FILE_VERIFY, PII_READ, ASSISTANT_USE, STORAGE_READ),
            ROLE_ANALYST, Set.of(FILE_READ, METERS_READ, INCIDENT_READ, POLICY_READ, INTELLIGENCE_READ,
                    INTELLIGENCE_WRITE, ASSISTANT_USE, STORAGE_READ),
            ROLE_INCIDENT_MANAGER, Set.of(FILE_READ, METERS_READ, INCIDENT_READ, INCIDENT_CREATE, INCIDENT_WRITE,
                    INCIDENT_CONFIRM, POLICY_READ, INTELLIGENCE_READ, ASSISTANT_USE, VOICE_USE, STORAGE_READ),
            ROLE_ADMIN, Set.of(FILE_READ, FILE_WRITE, FILE_VERIFY, FILE_READ_SUSPECT, PII_READ, METERS_READ,
                    INCIDENT_READ, INCIDENT_CREATE, INCIDENT_WRITE, INCIDENT_CONFIRM, POLICY_READ, POLICY_WRITE,
                    INTELLIGENCE_READ, INTELLIGENCE_WRITE, ASSISTANT_USE, VOICE_USE, STORAGE_READ),
            ROLE_SVC_METER_INGEST, Set.of(METERS_WRITE),
            ROLE_SVC_ALERT_INGEST, Set.of(ALERTS_INGEST),
            ROLE_SVC_INTELLIGENCE, Set.of(METERS_READ, INCIDENT_READ, INCIDENT_CREATE));

    private RolePermissions() {
    }

    /** @return the roles this application knows (others in the token, e.g. {@code offline_access}, are ignored) */
    public static Set<String> knownRoles() {
        return MATRIX.keySet();
    }

    /** @return every permission granted by the given roles, sorted; unknown roles grant nothing */
    public static Set<String> permissionsOf(Collection<String> roles) {
        Set<String> permissions = new TreeSet<>();
        for (String role : roles) {
            permissions.addAll(MATRIX.getOrDefault(role, Set.of()));
        }
        return permissions;
    }
}
