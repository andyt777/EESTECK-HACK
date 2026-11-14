package ro.filegrid.policy;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.time.Duration;
import org.springframework.boot.convert.DurationStyle;

/**
 * Reads the policy YAML into {@link Policy}. Parsing is strict: an unknown
 * key, an unknown category or storage name, or a wrong type is an error, so a
 * typo can never be silently ignored.
 */
public final class PolicyParser {

    /** Larger documents are rejected before parsing. */
    public static final int MAX_CONTENT_CHARS = 256 * 1024;

    private static final YAMLMapper YAML = YAMLMapper.builder()
            .addModule(new SimpleModule()
                    .addDeserializer(Duration.class, new DurationDeserializer())
                    .addSerializer(Duration.class, new DurationSerializer()))
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .build();

    private PolicyParser() {
    }

    /**
     * @throws PolicySyntaxException when the text is not a policy document
     */
    public static Policy parse(String yaml) {
        if (yaml == null || yaml.isBlank()) {
            throw new PolicySyntaxException("the document is empty");
        }
        if (yaml.length() > MAX_CONTENT_CHARS) {
            throw new PolicySyntaxException("the document is larger than " + MAX_CONTENT_CHARS + " characters");
        }
        try {
            Policy policy = YAML.readValue(yaml, Policy.class);
            if (policy == null) {
                throw new PolicySyntaxException("the document is empty");
            }
            return policy;
        } catch (JsonProcessingException e) {
            throw new PolicySyntaxException(e.getOriginalMessage() + location(e));
        }
    }

    /** The policy as a JSON tree (used by the API and to compute the diff between versions). */
    public static JsonNode toJson(Policy policy) {
        return YAML.valueToTree(policy);
    }

    private static String location(JsonProcessingException e) {
        return e.getLocation() == null ? "" : " (line " + e.getLocation().getLineNr() + ")";
    }

    /** The text is not valid YAML or does not have the shape of a policy. */
    public static class PolicySyntaxException extends RuntimeException {
        public PolicySyntaxException(String message) {
            super(message);
        }
    }

    /** Accepts {@code 500ms}, {@code 10s}, {@code 2m} and ISO-8601 ({@code PT10S}). */
    private static final class DurationDeserializer extends StdDeserializer<Duration> {
        DurationDeserializer() {
            super(Duration.class);
        }

        @Override
        public Duration deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            String text = parser.getValueAsString();
            try {
                return DurationStyle.detectAndParse(text.trim());
            } catch (RuntimeException e) {
                return (Duration) context.handleWeirdStringValue(Duration.class, text,
                        "expected a duration such as 500ms, 10s or 2m");
            }
        }
    }

    private static final class DurationSerializer extends StdSerializer<Duration> {
        DurationSerializer() {
            super(Duration.class);
        }

        @Override
        public void serialize(Duration value, JsonGenerator generator, SerializerProvider provider)
                throws IOException {
            generator.writeString(value.toMillis() % 1000 == 0
                    ? value.toSeconds() + "s"
                    : value.toMillis() + "ms");
        }
    }
}
