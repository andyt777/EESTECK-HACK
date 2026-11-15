package ro.filegrid.whatsapp;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a WhatsApp message asks for. Deterministic: media type first, then a few fixed words; anything else
 * is a question for the assistant.
 */
public final class WhatsAppIntents {

    public enum Kind { CONFIRM, APPROVE, REJECT, REPORT_TEXT, REPORT_VOICE, REPORT_PHOTO, QUESTION, UNSUPPORTED, EMPTY }

    /** {@code incidentCode} only for CONFIRM ("OK INC-0007"); {@code text} the report text or the question. */
    public record Intent(Kind kind, String incidentCode, String text) {
    }

    private static final Pattern OK = Pattern.compile("^ok(?:\\s+(inc-\\d+(?:-\\d+)*))?$");
    private static final Pattern REPORT = Pattern.compile("^raport\\b[\\s:.,-]*", Pattern.CASE_INSENSITIVE
            | Pattern.UNICODE_CASE);
    private static final Set<String> YES = Set.of("da", "yes", "confirm", "confirma");
    private static final Set<String> NO = Set.of("nu", "no", "anuleaza", "renunt");

    private WhatsAppIntents() {
    }

    public static Intent parse(String body, String mediaContentType) {
        String text = body == null ? "" : body.strip();
        String media = mediaContentType == null ? "" : mediaContentType.toLowerCase(Locale.ROOT);
        if (media.startsWith("audio/")) {
            return new Intent(Kind.REPORT_VOICE, null, text);
        }
        if (media.startsWith("image/")) {
            return new Intent(Kind.REPORT_PHOTO, null, text);
        }
        if (!media.isEmpty()) {
            return new Intent(Kind.UNSUPPORTED, null, text);
        }
        if (text.isEmpty()) {
            return new Intent(Kind.EMPTY, null, "");
        }
        String word = fold(text).replaceAll("[\\s.!?]+$", "").replaceAll("\\s+", " ");
        Matcher ok = OK.matcher(word);
        if (ok.matches()) {
            return new Intent(Kind.CONFIRM, ok.group(1) == null ? null : ok.group(1).toUpperCase(Locale.ROOT), "");
        }
        if (YES.contains(word)) {
            return new Intent(Kind.APPROVE, null, "");
        }
        if (NO.contains(word)) {
            return new Intent(Kind.REJECT, null, "");
        }
        Matcher report = REPORT.matcher(text);
        if (report.lookingAt()) {
            return new Intent(Kind.REPORT_TEXT, null, text.substring(report.end()).strip());
        }
        return new Intent(Kind.QUESTION, null, text);
    }

    /** Lower case without diacritics ("Anulează" -> "anuleaza"). */
    static String fold(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }
}
