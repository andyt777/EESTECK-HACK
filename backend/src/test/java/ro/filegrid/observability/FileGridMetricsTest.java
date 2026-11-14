package ro.filegrid.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import ro.filegrid.file.FileCategory;
import ro.filegrid.storage.StorageType;

class FileGridMetricsTest {

    private final PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    private final FileGridMetrics metrics = new FileGridMetrics(registry);

    @Test
    void storeDurationIsAPercentileHistogramWithTheBusinessTags() {
        metrics.recordStore(FileCategory.FAULT_RECORD, StorageType.FTP, FileGridMetrics.VERIFIED,
                TimeUnit.MILLISECONDS.toNanos(120), 2048);
        metrics.recordFallback(FileCategory.FAULT_RECORD, StorageType.FTP, StorageType.S3);

        String scrape = registry.scrape();
        assertThat(scrape).contains("filegrid_store_duration_seconds_bucket{")
                .contains("category=\"FAULT_RECORD\"")
                .contains("storage=\"FTP\"")
                .contains("outcome=\"verified\"")
                .contains("filegrid_operations_total{")
                .contains("filegrid_bytes_total{")
                .contains("filegrid_fallback_total{");
    }

    @Test
    void retrievalIsReportedOnceWhenTheStreamIsClosed() throws IOException {
        try (InputStream in = new TimedInputStream(new ByteArrayInputStream(new byte[5000]),
                (outcome, nanos, bytes) -> metrics.recordRetrieve(FileCategory.DRONE_VIDEO, StorageType.S3,
                        outcome, nanos, bytes))) {
            in.readAllBytes();
        }
        assertThat(registry.get("filegrid.retrieve.duration").tag("outcome", "success").timer().count())
                .isEqualTo(1);
        assertThat(registry.get("filegrid.bytes").tag("operation", "retrieve").counter().count()).isEqualTo(5000);
        assertThat(registry.scrape()).contains("filegrid_retrieve_duration_seconds_bucket{");
    }

    @Test
    void aFailedReadIsReportedAsError() {
        InputStream failing = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("connection reset");
            }
        };
        assertThatThrownBy(() -> {
            try (InputStream in = new TimedInputStream(failing, (outcome, nanos, bytes) ->
                    metrics.recordRetrieve(FileCategory.FAULT_RECORD, StorageType.FTP, outcome, nanos, bytes))) {
                in.read(new byte[10]);
            }
        }).isInstanceOf(IOException.class);
        assertThat(registry.get("filegrid.retrieve.duration").tag("outcome", "error").timer().count()).isEqualTo(1);
    }
}
