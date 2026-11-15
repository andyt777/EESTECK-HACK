package ro.filegrid.whatsapp;

import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;

/**
 * The people allowed to talk to PPCastor on WhatsApp: exactly the numbers configured for notifications
 * ({@code NOTIFY_CONTACT_<ROLE>} environment variables, never in the repo). Anyone else gets no action.
 */
public class WhatsAppContacts {

    static final String PREFIX = "NOTIFY_CONTACT_";

    /** A known sender: its role key and the number exactly as configured (used for the notification mask). */
    public record Match(String role, String configuredPhone) {
    }

    private final Map<String, String> env;

    public WhatsAppContacts(Map<String, String> env) {
        this.env = env;
    }

    /** Every {@code NOTIFY_CONTACT_*} value visible to Spring (OS environment, test properties, ...). */
    public static WhatsAppContacts from(ConfigurableEnvironment environment) {
        Map<String, String> found = new TreeMap<>();
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (source instanceof EnumerablePropertySource<?> enumerable) {
                for (String name : enumerable.getPropertyNames()) {
                    if (name.startsWith(PREFIX) && !found.containsKey(name)) {
                        String value = environment.getProperty(name);
                        if (value != null) {
                            found.put(name, value);
                        }
                    }
                }
            }
        }
        return new WhatsAppContacts(found);
    }

    public Optional<Match> find(String phone) {
        String wanted = normalise(phone);
        if (wanted.length() < 8) {
            return Optional.empty();
        }
        return env.entrySet().stream()
                .filter(e -> e.getKey().startsWith(PREFIX) && e.getValue() != null)
                .filter(e -> wanted.equals(normalise(e.getValue())))
                .map(e -> new Match(e.getKey().substring(PREFIX.length()), e.getValue().trim()))
                .sorted((a, b) -> a.role().compareTo(b.role()))
                .findFirst();
    }

    /** The role key (e.g. {@code DISPATCH}, {@code FIELD_TEAM_TULCEA}) of a sender, if it is a known contact. */
    public Optional<String> roleOf(String phone) {
        return find(phone).map(Match::role);
    }

    /** {@code whatsapp:+40 (722) 000-111} -> {@code +40722000111}. */
    public static String normalise(String phone) {
        if (phone == null) {
            return "";
        }
        String p = phone.strip();
        if (p.startsWith("whatsapp:")) {
            p = p.substring("whatsapp:".length());
        }
        String digits = p.replaceAll("[^0-9]", "");
        return digits.isEmpty() ? "" : "+" + digits;
    }
}
