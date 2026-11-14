package ro.filegrid.incident;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Renders the WhatsApp templates of {@code config/templates/}.
 *
 * <ul>
 *   <li>a line with a placeholder that has no value is left out entirely, so a
 *       message never shows an invented cause, duration or number of clients;</li>
 *   <li>every value is sanitised: secrets, tokens and personal identifiers never
 *       reach a message.</li>
 * </ul>
 * Templates are read from disk on every call, so they can be changed without a restart.
 */
@Component
public class MessageRenderer {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([a-z_]+)\\s*}}");
    private static final int MAX_VALUE_LENGTH = 400;

    /** key=value or key: value where the key looks like a credential. */
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)\\b(password|passwd|parola|secret|token|api[_-]?key|access[_-]?key|authorization|bearer)"
                    + "\\b\\s*[:=]?\\s*\\S+");
    /** Romanian personal numeric code (CNP): 13 digits starting with 1-9. */
    private static final Pattern CNP = Pattern.compile("\\b[1-9]\\d{12}\\b");
    private static final Pattern IBAN = Pattern.compile("(?i)\\bRO\\d{2}[A-Z]{4}[0-9A-Z]{16}\\b");
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.]+");
    private static final Pattern PHONE = Pattern.compile(
            "(?<![\\w.:/-])(?:\\+\\d[\\d .-]{8,}\\d|0\\d{2,3}[ .-]?\\d{3}[ .-]?\\d{3,4})(?![\\w.:/-])");
    private static final Pattern LONG_TOKEN = Pattern.compile("\\b[A-Za-z0-9_\\-]{32,}\\b");
    private static final Pattern SHA256 = Pattern.compile("\\b[a-f0-9]{64}\\b");
    private static final Pattern UUID_PATTERN =
            Pattern.compile("\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b");

    private final Path templatesDir;

    public MessageRenderer(@Value("${filegrid.notify.templates-dir:../config/templates}") String templatesDir) {
        this.templatesDir = Path.of(templatesDir);
    }

    public String render(String template, Map<String, String> values) {
        return renderText(load(template), values);
    }

    String load(String template) {
        if (!template.matches("[a-z-]+")) {
            throw new IllegalArgumentException("Invalid template name " + template);
        }
        try {
            return Files.readString(templatesDir.resolve(template + ".txt"), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Notification template " + template + " cannot be read", e);
        }
    }

    /** Fills the placeholders; lines with a missing value are dropped. */
    public static String renderText(String text, Map<String, String> values) {
        List<String> out = new ArrayList<>();
        for (String line : text.replace("\r\n", "\n").split("\n", -1)) {
            Matcher m = PLACEHOLDER.matcher(line);
            StringBuilder rendered = new StringBuilder();
            boolean missing = false;
            while (m.find()) {
                String value = values.get(m.group(1));
                if (value == null || value.isBlank()) {
                    missing = true;
                    break;
                }
                m.appendReplacement(rendered, Matcher.quoteReplacement(sanitize(value)));
            }
            if (missing) {
                continue;
            }
            m.appendTail(rendered);
            out.add(rendered.toString());
        }
        // No double blank lines left behind by dropped lines.
        String joined = String.join("\n", out).replaceAll("\n{3,}", "\n\n");
        return joined.strip() + "\n";
    }

    /**
     * Removes anything that must never travel in a notification. File ids (UUIDs)
     * and hashes are kept: they identify, they do not expose.
     */
    public static String sanitize(String value) {
        String clean = value.replace('\n', ' ').replace('\r', ' ').replace("*", "").replace("{{", "")
                .strip();
        clean = SECRET_ASSIGNMENT.matcher(clean).replaceAll("[ascuns]");
        clean = CNP.matcher(clean).replaceAll("[CNP ascuns]");
        clean = IBAN.matcher(clean).replaceAll("[IBAN ascuns]");
        clean = EMAIL.matcher(clean).replaceAll("[e-mail ascuns]");
        clean = PHONE.matcher(clean).replaceAll("[telefon ascuns]");
        StringBuilder sb = new StringBuilder();
        Matcher token = LONG_TOKEN.matcher(clean);
        while (token.find()) {
            String t = token.group();
            boolean keep = SHA256.matcher(t).matches() || UUID_PATTERN.matcher(t).matches() || t.contains("-")
                    && UUID_PATTERN.matcher(t).find();
            token.appendReplacement(sb, Matcher.quoteReplacement(keep ? t : "[ascuns]"));
        }
        token.appendTail(sb);
        clean = sb.toString();
        if (clean.length() > MAX_VALUE_LENGTH) {
            clean = clean.substring(0, MAX_VALUE_LENGTH - 1) + "…";
        }
        return clean;
    }
}
