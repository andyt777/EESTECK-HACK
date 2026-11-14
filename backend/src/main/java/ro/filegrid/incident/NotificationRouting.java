package ro.filegrid.incident;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import ro.filegrid.incident.Incidents.IncidentType;
import ro.filegrid.incident.Incidents.Severity;

/**
 * {@code config/notification-routing.yaml}: incident type + severity → roles,
 * escalation chain and timeouts. Re-read when the file changes. Contacts come
 * from the environment ({@code NOTIFY_CONTACT_<ROLE>}), never from the repo.
 */
@Component
public class NotificationRouting {

    private static final Logger log = LoggerFactory.getLogger(NotificationRouting.class);
    private static final YAMLMapper YAML = new YAMLMapper();

    /** @param levels escalation chain; element 0 is informed at once */
    public record Rule(List<IncidentType> types, String template, Severity minSeverity, List<List<String>> levels) {
    }

    /** A person to inform. {@code phone} is null when no number is configured. */
    public record Contact(String role, String roleLabel, String name, String phone) {
        public String masked() {
            if (phone == null || phone.length() < 6) {
                return null;
            }
            return phone.substring(0, 4) + "*****" + phone.substring(phone.length() - 3);
        }
    }

    private record Config(List<Rule> rules, Map<String, String> roles, Map<Severity, Duration> escalation) {
    }

    private final Path file;
    private final Environment env;
    private volatile Config config;
    private volatile long loadedModified = -1;

    public NotificationRouting(@Value("${filegrid.notify.routing-file:../config/notification-routing.yaml}")
                               String file, Environment env) {
        this.file = Path.of(file);
        this.env = env;
    }

    private Config config() {
        try {
            long modified = Files.getLastModifiedTime(file).toMillis();
            if (config == null || modified != loadedModified) {
                config = parse(YAML.readTree(file.toFile()));
                loadedModified = modified;
                log.info("Notification routing loaded from {}: {} rule(s)", file, config.rules().size());
            }
        } catch (IOException | RuntimeException e) {
            if (config == null) {
                throw new IllegalStateException("Notification routing " + file + " cannot be read", e);
            }
            log.warn("Notification routing {} is invalid, the previous version stays active: {}", file,
                    e.getMessage());
        }
        return config;
    }

    static Config parse(JsonNode root) {
        Map<String, String> roles = new LinkedHashMap<>();
        root.path("roles").fields().forEachRemaining(e -> roles.put(e.getKey(), e.getValue().asText()));
        Map<Severity, Duration> escalation = new EnumMap<>(Severity.class);
        root.path("escalation").fields().forEachRemaining(e ->
                escalation.put(Severity.valueOf(e.getKey()), duration(e.getValue().asText())));
        List<Rule> rules = new ArrayList<>();
        for (JsonNode r : root.path("rules")) {
            List<IncidentType> types = new ArrayList<>();
            r.path("types").forEach(t -> types.add(IncidentType.valueOf(t.asText())));
            List<List<String>> levels = new ArrayList<>();
            for (JsonNode level : r.path("levels")) {
                List<String> l = new ArrayList<>();
                level.forEach(role -> {
                    if (!roles.containsKey(role.asText())) {
                        throw new IllegalArgumentException("Unknown role " + role.asText());
                    }
                    l.add(role.asText());
                });
                levels.add(l);
            }
            rules.add(new Rule(types, r.path("template").asText(),
                    Severity.valueOf(r.path("minSeverity").asText("LOW")), levels));
        }
        return new Config(rules, roles, escalation);
    }

    private static Duration duration(String text) {
        String t = text.trim().toLowerCase(Locale.ROOT);
        try {
            if (t.endsWith("ms")) {
                return Duration.ofMillis(Long.parseLong(t.substring(0, t.length() - 2)));
            }
            long n = Long.parseLong(t.substring(0, t.length() - 1));
            return switch (t.charAt(t.length() - 1)) {
                case 's' -> Duration.ofSeconds(n);
                case 'm' -> Duration.ofMinutes(n);
                case 'h' -> Duration.ofHours(n);
                case 'd' -> Duration.ofDays(n);
                default -> Duration.parse(text);
            };
        } catch (NumberFormatException | DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid duration " + text, e);
        }
    }

    public Rule ruleFor(IncidentType type) {
        return config().rules().stream().filter(r -> r.types().contains(type)).findFirst().orElse(null);
    }

    public String roleLabel(String role) {
        return config().roles().getOrDefault(role, role);
    }

    /** How long a notification may stay unconfirmed at this severity. A test may override it. */
    public Duration escalationAfter(Severity severity) {
        String override = env.getProperty("filegrid.notify.escalation-override");
        if (override != null && !override.isBlank()) {
            return duration(override);
        }
        return config().escalation().getOrDefault(severity, Duration.ofMinutes(30));
    }

    public Map<String, Long> escalationSeconds() {
        Map<String, Long> out = new LinkedHashMap<>();
        for (Severity s : Severity.values()) {
            out.put(s.name(), escalationAfter(s).toSeconds());
        }
        return out;
    }

    /**
     * The person behind a role. The zone field team may have its own number
     * ({@code NOTIFY_CONTACT_FIELD_TEAM_<ZONE>}).
     */
    public Contact contact(String role, String zone) {
        String phone = null;
        if (zone != null && "FIELD_TEAM".equals(role)) {
            String zoneKey = zone.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
            phone = env.getProperty("NOTIFY_CONTACT_FIELD_TEAM_" + zoneKey);
        }
        if (phone == null) {
            phone = env.getProperty("NOTIFY_CONTACT_" + role);
        }
        String name = env.getProperty("NOTIFY_NAME_" + role);
        String label = roleLabel(role);
        if (name == null || name.isBlank()) {
            name = "FIELD_TEAM".equals(role) && zone != null ? label + " " + zone : label;
        }
        return new Contact(role, label, name, phone == null || phone.isBlank() ? null : phone.trim());
    }
}
