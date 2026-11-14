package ro.filegrid.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import ro.filegrid.testsupport.DeterministicContent;

class ProgressInputStreamTest {

    private static final long MB = 1024 * 1024;

    @Test
    void stepIsFivePercentButNeverMoreThan16MegabytesNorLessThan64Kilobytes() {
        assertThat(ProgressInputStream.stepFor(100 * MB)).isEqualTo(5 * MB);
        assertThat(ProgressInputStream.stepFor(1536 * MB)).isEqualTo(16 * MB);
        assertThat(ProgressInputStream.stepFor(100 * 1024)).isEqualTo(64 * 1024);
    }

    @Test
    void reportsAboutTwentyTimesForAMediumFileAndCountsEveryByte() throws IOException {
        long size = 100 * MB;
        List<Long> reports = new ArrayList<>();
        try (InputStream in = new ProgressInputStream(new DeterministicContent(1, size).open(), size, reports::add)) {
            byte[] buffer = new byte[64 * 1024];
            while (in.read(buffer) != -1) {
                // drain
            }
        }
        assertThat(reports).hasSize(20);
        assertThat(reports).isSorted();
        assertThat(reports.get(reports.size() - 1)).isEqualTo(size);
    }
}
