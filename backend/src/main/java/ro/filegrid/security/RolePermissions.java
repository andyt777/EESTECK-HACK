package ro.filegrid.security;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Role to permission matrix of docs/AUTH-DESIGN.md §3. Roles are realm roles of the
 * {@code filegrid} realm; only {@code connections} and {@code admin} may read personal data.
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

    private static final Map<String, Set<String>> MATRIX = Map.of(
            "field", Set.of(FILE_READ, FILE_WRITE, INCIDENT_READ, INCIDENT_CREATE, ASSISTANT_USE, VOICE_USE,
                    STORAGE_READ),
            "dispatcher", Set.of(FILE_READ, FILE_VERIFY, METERS_READ, INCIDENT_READ, INCIDENT_CREATE,
                    INCIDENT_WRITE, INCIDENT_CONFIRM, POLICY_READ, INTELLIGENCE_READ, ASSISTANT_USE, VOICE_USE,
                    STORAGE_READ),
            "connections", Set.of(FILE_READ, FILE_WRITE, FILE_VERIFY, PII_READ, ASSISTANT_USE, STORAGE_READ),
            "analyst", Set.of(FILE_READ, METERS_READ, INCIDENT_READ, POLICY_READ, INTELLIGENCE_READ,
                    INTELLIGENCE_WRITE, ASSISTANT_USE, STORAGE_READ),
            "incident-manager", Set.of(FILE_READ, METERS_READ, INCIDENT_READ, INCIDENT_CREATE, INCIDENT_WRITE,
                    INCIDENT_CONFIRM, POLICY_READ, INTELLIGENCE_READ, ASSISTANT_USE, VOICE_USE, STORAGE_READ),
            "admin", Set.of(FILE_READ, FILE_WRITE, FILE_VERIFY, FILE_READ_SUSPECT, PII_READ, METERS_READ,
                    INCIDENT_READ, INCIDENT_CREATE, INCIDENT_WRITE, INCIDENT_CONFIRM, POLICY_READ, POLICY_WRITE,
                    INTELLIGENCE_READ, INTELLIGENCE_WRITE, ASSISTANT_USE, VOICE_USE, STORAGE_READ),
            "svc-meter-ingest", Set.of(METERS_WRITE),
            "svc-alert-ingest", Set.of(ALERTS_INGEST),
            "svc-intelligence", Set.of(METERS_READ, INCIDENT_READ, INCIDENT_CREATE));

    private RolePermissions() {
    }

    /** Union of the permissions of the given roles (unknown roles grant nothing). */
    public static Set<String> of(Collection<String> roles) {
        Set<String> permissions = new TreeSet<>();
        for (String role : roles) {
            permissions.addAll(MATRIX.getOrDefault(role, Set.of()));
        }
        return permissions;
    }
}
