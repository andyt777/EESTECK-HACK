package ro.filegrid.upload;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ro.filegrid.file.DataOrigin;
import ro.filegrid.file.FileCategory;

/**
 * Parser for tus 1.0 {@code Upload-Metadata} header according to the tus specification.
 * Decodes comma-separated {@code key [base64value]} pairs and maps them to domain fields.
 */
@Component
public class TusMetadataParser {

    private static final Logger log = LoggerFactory.getLogger(TusMetadataParser.class);

    public record ParsedTusMetadata(
            Optional<FileCategory> category,
            Optional<String> zone,
            Optional<String> station,
            Optional<String> equipment,
            Optional<String> meterId,
            Optional<String> eventType,
            Optional<Instant> eventTime,
            Optional<String> description,
            DataOrigin dataOrigin,
            Optional<String> filename,
            Optional<String> contentType,
            Map<String, String> rawKeyValues
    ) {
        /**
         * Returns operational context map for classification and routing.
         */
        public Map<String, String> toContextMap() {
            Map<String, String> map = new HashMap<>();
            zone.ifPresent(v -> map.put("zone", v));
            station.ifPresent(v -> map.put("station", v));
            equipment.ifPresent(v -> map.put("equipment", v));
            meterId.ifPresent(v -> map.put("meterId", v));
            eventType.ifPresent(v -> map.put("eventType", v));
            eventTime.ifPresent(v -> map.put("eventTime", v.toString()));
            description.ifPresent(v -> map.put("description", v));
            return Collections.unmodifiableMap(map);
        }
    }

    public ParsedTusMetadata parse(String uploadMetadataHeader) {
        Map<String, String> raw = parseHeader(uploadMetadataHeader);

        Optional<FileCategory> category = Optional.ofNullable(raw.get("category"))
                .filter(s -> !s.isBlank())
                .flatMap(this::safeParseCategory);

        Optional<String> zone = Optional.ofNullable(raw.get("zone")).filter(s -> !s.isBlank());
        Optional<String> station = Optional.ofNullable(raw.get("station")).filter(s -> !s.isBlank());
        Optional<String> equipment = Optional.ofNullable(raw.get("equipment")).filter(s -> !s.isBlank());
        Optional<String> meterId = Optional.ofNullable(raw.get("meterId")).filter(s -> !s.isBlank());
        Optional<String> eventType = Optional.ofNullable(raw.get("eventType")).filter(s -> !s.isBlank());

        Optional<Instant> eventTime = Optional.ofNullable(raw.get("eventTime"))
                .filter(s -> !s.isBlank())
                .flatMap(this::safeParseInstant);

        Optional<String> description = Optional.ofNullable(raw.get("description")).filter(s -> !s.isBlank());

        DataOrigin dataOrigin = Optional.ofNullable(raw.get("dataOrigin"))
                .filter(s -> !s.isBlank())
                .map(String::trim)
                .map(String::toUpperCase)
                .flatMap(this::safeParseDataOrigin)
                .orElse(DataOrigin.REAL);

        Optional<String> filename = Optional.ofNullable(raw.get("filename"))
                .or(() -> Optional.ofNullable(raw.get("name")))
                .filter(s -> !s.isBlank());

        Optional<String> contentType = Optional.ofNullable(raw.get("contentType"))
                .or(() -> Optional.ofNullable(raw.get("filetype")))
                .filter(s -> !s.isBlank());

        return new ParsedTusMetadata(
                category, zone, station, equipment, meterId,
                eventType, eventTime, description, dataOrigin,
                filename, contentType, Collections.unmodifiableMap(raw)
        );
    }

    public static Map<String, String> parseHeader(String header) {
        if (header == null || header.isBlank()) {
            return Map.of();
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (String element : header.split(",")) {
            String trimmed = element.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = trimmed.split("\\s+", 2);
            String key = parts[0];
            String value = "";
            if (parts.length > 1 && !parts[1].isBlank()) {
                try {
                    byte[] decoded = Base64.getDecoder().decode(parts[1].trim());
                    value = new String(decoded, StandardCharsets.UTF_8);
                } catch (IllegalArgumentException ex) {
                    log.warn("Malformed Base64 value for metadata key: {}", key);
                }
            }
            map.put(key, value);
        }
        return Collections.unmodifiableMap(map);
    }

    private Optional<FileCategory> safeParseCategory(String raw) {
        try {
            return Optional.of(FileCategory.valueOf(raw.trim().toUpperCase()));
        } catch (IllegalArgumentException e) {
            log.warn("Unknown FileCategory in metadata: {}", raw);
            return Optional.empty();
        }
    }

    private Optional<DataOrigin> safeParseDataOrigin(String raw) {
        try {
            return Optional.of(DataOrigin.valueOf(raw));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private Optional<Instant> safeParseInstant(String raw) {
        try {
            return Optional.of(Instant.parse(raw.trim()));
        } catch (DateTimeParseException e) {
            log.warn("Unparseable ISO-8601 eventTime in metadata: {}", raw);
            return Optional.empty();
        }
    }
}
