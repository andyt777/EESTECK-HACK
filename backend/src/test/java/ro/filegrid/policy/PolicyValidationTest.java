package ro.filegrid.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import ro.filegrid.file.FileCategory;
import ro.filegrid.policy.Policy.CategoryPolicy;
import ro.filegrid.policy.PolicyParser.PolicySyntaxException;
import ro.filegrid.storage.StorageType;
import ro.filegrid.text.Texts;

class PolicyValidationTest {

    private final PolicyValidator validator = new PolicyValidator(new Texts());

    private static String loadRepoPolicyYaml() throws IOException {
        return Files.readString(Path.of("../config/policies.yaml"));
    }

    @Test
    void repoPoliciesYamlIsValidAndMatchesArchitecture() throws IOException {
        Policy policy = PolicyParser.parse(loadRepoPolicyYaml());

        assertThat(validator.validate(policy)).isEmpty();
        assertThat(policy.schemaVersion()).isEqualTo(1);
        assertThat(policy.storages()).containsOnlyKeys(StorageType.values());
        assertThat(policy.categories()).containsOnlyKeys(FileCategory.values());

        CategoryPolicy meter = policy.category(FileCategory.METER_READING);
        assertThat(meter.primary()).isEqualTo(StorageType.DATABASE);
        assertThat(meter.fallbacks()).containsExactly(StorageType.S3);
        assertThat(meter.constraints().idempotent()).isTrue();
        assertThat(meter.constraints().maxSizeBytes()).containsEntry(StorageType.DATABASE, 1_048_576L);

        CategoryPolicy fault = policy.category(FileCategory.FAULT_RECORD);
        assertThat(fault.primary()).isEqualTo(StorageType.FTP);
        assertThat(fault.fallbacks()).containsExactly(StorageType.S3);
        assertThat(fault.constraints().needsImmediateRead()).isTrue();

        CategoryPolicy document = policy.category(FileCategory.CONNECTION_DOCUMENT);
        assertThat(document.primary()).isEqualTo(StorageType.S3);
        assertThat(document.fallbacks()).containsExactly(StorageType.DATABASE);
        assertThat(document.constraints().needsEncryption()).isTrue();

        CategoryPolicy video = policy.category(FileCategory.DRONE_VIDEO);
        assertThat(video.primary()).isEqualTo(StorageType.S3);
        assertThat(video.fallbacks()).containsExactly(StorageType.FTP);
        assertThat(video.constraints().needsResumable()).isTrue();
    }

    @Test
    void rejectsMalformedUnknownAndDuplicateYamlKeys() {
        assertThatThrownBy(() -> PolicyParser.parse(""))
                .isInstanceOf(PolicySyntaxException.class);
        assertThatThrownBy(() -> PolicyParser.parse("schemaVersion: [unclosed"))
                .isInstanceOf(PolicySyntaxException.class);
        assertThatThrownBy(() -> PolicyParser.parse("schemaVersion: 1\nunknownRootKey: true"))
                .isInstanceOf(PolicySyntaxException.class);
        assertThatThrownBy(() -> PolicyParser.parse("schemaVersion: 1\nschemaVersion: 1"))
                .isInstanceOf(PolicySyntaxException.class);
    }

    @Test
    void rejectsInvalidSchemaVersionAndMissingEntries() throws IOException {
        String wrongVersion = loadRepoPolicyYaml().replace("schemaVersion: 1", "schemaVersion: 99");
        List<String> versionErrors = validator.validate(PolicyParser.parse(wrongVersion));
        assertThat(versionErrors).anyMatch(e -> e.contains("schemaVersion"));

        String missingStorage = loadRepoPolicyYaml().replaceAll("(?sm)^  FTP:.*?^categories:", "categories:");
        List<String> storageErrors = validator.validate(PolicyParser.parse(missingStorage));
        assertThat(storageErrors).anyMatch(e -> e.contains("storages.FTP"));

        String missingCategory = loadRepoPolicyYaml().replaceAll("(?s)  DRONE_VIDEO:.*\\z", "");
        List<String> categoryErrors = validator.validate(PolicyParser.parse(missingCategory));
        assertThat(categoryErrors).anyMatch(e -> e.contains("categories.DRONE_VIDEO"));
    }

    @Test
    void rejectsInvalidConstraintsWeightsRetryAndDetection() throws IOException {
        String base = loadRepoPolicyYaml();

        String badLimitsAndFallbacks = base
                .replace("maxSizeBytes: 67108864", "maxSizeBytes: 0")
                .replace("fallbacks: [S3]\n    constraints:\n      needsResumable: false\n      needsEncryption: false\n      needsImmediateRead: false\n      idempotent: true\n      maxSizeBytes:\n        DATABASE: 1048576",
                        "fallbacks: [DATABASE, S3, S3]\n    constraints:\n      needsResumable: false\n      needsEncryption: false\n      needsImmediateRead: false\n      idempotent: true\n      maxSizeBytes:\n        DATABASE: -5");
        List<String> errors = validator.validate(PolicyParser.parse(badLimitsAndFallbacks));
        assertThat(errors).anyMatch(e -> e.contains("storages.DATABASE.maxSizeBytes"));
        assertThat(errors).anyMatch(e -> e.contains("categories.METER_READING.constraints.maxSizeBytes.DATABASE"));
        assertThat(errors).anyMatch(e -> e.contains("este deja storage-ul principal"));
        assertThat(errors).anyMatch(e -> e.contains("apare de două ori"));

        String badWeightsAndRetry = base
                .replace("policyPreference: 4\n    retry:\n      maxAttempts: 5\n      initialBackoff: 500ms\n      maxBackoff: 10s",
                        "policyPreference: -1\n    retry:\n      maxAttempts: 25\n      initialBackoff: 20s\n      maxBackoff: 5s");
        List<String> weightRetryErrors = validator.validate(PolicyParser.parse(badWeightsAndRetry));
        assertThat(weightRetryErrors).anyMatch(e -> e.contains("ponderea nu poate fi negativă"));
        assertThat(weightRetryErrors).anyMatch(e -> e.contains("maxAttempts"));
        assertThat(weightRetryErrors).anyMatch(e -> e.contains("maxBackoff nu poate fi mai mic decât initialBackoff"));

        String zeroWeights = base.replace(
                "    weights:\n      sizeFit: 1\n      resumable: 0\n      availability: 3\n      measuredLatencyP95: 2\n      policyPreference: 4",
                "    weights:\n      sizeFit: 0\n      resumable: 0\n      availability: 0\n      measuredLatencyP95: 0\n      policyPreference: 0");
        assertThat(validator.validate(PolicyParser.parse(zeroWeights)))
                .anyMatch(e -> e.contains("cel puțin o pondere trebuie să fie mai mare decât zero"));

        String badDetection = base
                .replace("extensions: [json]", "extensions: [.JSON]")
                .replace("mimeTypes: [application/json]", "mimeTypes: [not-a-mime]");
        List<String> detectionErrors = validator.validate(PolicyParser.parse(badDetection));
        assertThat(detectionErrors).anyMatch(e -> e.contains("extensie nevalidă"));
        assertThat(detectionErrors).anyMatch(e -> e.contains("tip MIME nevalid"));
    }

    @Test
    void policyDiffTracksValueChangesAndIgnoresComments() throws IOException {
        String originalYaml = loadRepoPolicyYaml();
        String modifiedYaml = originalYaml.replace("DATABASE: 1048576", "DATABASE: 2097152");
        String reformattedYaml = "# Extra comment\n\n" + originalYaml.replace("# What each storage can do.", "# Updated comment");

        Policy original = PolicyParser.parse(originalYaml);
        Policy modified = PolicyParser.parse(modifiedYaml);
        Policy reformatted = PolicyParser.parse(reformattedYaml);

        List<PolicyDiff.Change> changes = PolicyDiff.between(PolicyParser.toJson(original), PolicyParser.toJson(modified));
        assertThat(changes).singleElement().satisfies(change -> {
            assertThat(change.path()).isEqualTo("categories.METER_READING.constraints.maxSizeBytes.DATABASE");
            assertThat(change.before().asLong()).isEqualTo(1_048_576L);
            assertThat(change.after().asLong()).isEqualTo(2_097_152L);
        });

        assertThat(PolicyDiff.between(PolicyParser.toJson(original), PolicyParser.toJson(reformatted))).isEmpty();
    }
}
