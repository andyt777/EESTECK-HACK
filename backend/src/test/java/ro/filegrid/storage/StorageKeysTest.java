package ro.filegrid.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ro.filegrid.file.FileCategory;

class StorageKeysTest {

    private static final UUID ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final Instant AT = Instant.parse("2026-11-14T23:59:59Z");

    @Test
    void buildsCategoryYearMonthDayUuid() {
        assertThat(StorageKeys.forCategory(FileCategory.FAULT_RECORD, AT, ID))
                .isEqualTo("faults/2026/11/14/11111111-2222-3333-4444-555555555555");
    }

    @Test
    void everyCategoryHasItsOwnSegment() {
        assertThat(FileCategory.values())
                .extracting(FileCategory::storageSegment)
                .containsExactly("meters", "faults", "documents", "videos")
                .doesNotContain(StorageKeys.BENCHMARK_SEGMENT);
    }

    @Test
    void splitsSegmentAndPath() {
        String key = StorageKeys.forBenchmark(AT, ID);

        assertThat(StorageKeys.segmentOf(key)).isEqualTo("benchmark");
        assertThat(StorageKeys.pathWithinSegment(key)).isEqualTo("2026/11/14/" + ID);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "faults",                       // no object path
            "/faults/2026/x",               // absolute
            "faults/../documents/secret",   // traversal
            "faults/2026/..",
            "faults//x",
            "faults/2026/a b",              // whitespace
            "Faults/2026/x",                // segment must be lowercase letters
            "faults/2026/x\r\nDELE y"       // FTP command injection
    })
    void rejectsKeysThatAreNotPlainRelativePaths(String key) {
        assertThatIllegalArgumentException().isThrownBy(() -> StorageKeys.requireValid(key));
    }

    @Test
    void rejectsNull() {
        assertThatIllegalArgumentException().isThrownBy(() -> StorageKeys.requireValid(null));
    }
}
