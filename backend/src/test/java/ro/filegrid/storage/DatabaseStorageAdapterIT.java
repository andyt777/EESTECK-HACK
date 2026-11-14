package ro.filegrid.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ro.filegrid.file.FileCategory;
import ro.filegrid.storage.database.DatabaseStorageAdapter;
import ro.filegrid.testsupport.DeterministicContent;
import ro.filegrid.testsupport.TestImages;

@Testcontainers
class DatabaseStorageAdapterIT {

    private static final long LIMIT = 64L * 1024 * 1024;

    @Container
    static final PostgreSQLContainer<?> STORE_DB = new PostgreSQLContainer<>(TestImages.POSTGRES)
            .withDatabaseName("filegrid_store");

    static HikariDataSource dataSource;
    static JdbcTemplate jdbc;
    static DatabaseStorageAdapter adapter;

    @BeforeAll
    static void connect() {
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(STORE_DB.getJdbcUrl());
        dataSource.setUsername(STORE_DB.getUsername());
        dataSource.setPassword(STORE_DB.getPassword());
        dataSource.setMaximumPoolSize(3);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration/store").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        adapter = new DatabaseStorageAdapter(dataSource, jdbc,
                new StorageProperties(null, null, null, new StorageProperties.Database(LIMIT, 4 * 1024 * 1024)));
    }

    @AfterAll
    static void disconnect() {
        dataSource.close();
    }

    @Test
    void roundTripsAnObjectJustUnderTheLimitWithASmallHeap() {
        // 48 MB with a 256 MB heap: larger than what a few in-memory copies would allow.
        DeterministicContent content = new DeterministicContent(21, 48L * 1024 * 1024);
        String key = StorageKeys.forBenchmark(Instant.now(), UUID.randomUUID());

        StoredObject stored = adapter.put(key, content, content.size(), Map.of());

        String expected = content.sha256();
        assertThat(stored.sizeWritten()).isEqualTo(content.size());
        assertThat(stored.sha256()).isEqualTo(expected);
        assertThat(DeterministicContent.sha256Of(adapter.get(key))).isEqualTo(expected);
        assertThat(jdbc.queryForObject("SELECT sha256 FROM stored_blob WHERE key = ?", String.class, key))
                .isEqualTo(expected);
    }

    @Test
    void roundTripsATypicalMeterReading() {
        DeterministicContent content = new DeterministicContent(22, 12_345);
        String key = StorageKeys.forCategory(FileCategory.METER_READING, Instant.now(), UUID.randomUUID());

        adapter.put(key, content, content.size(), Map.of());

        assertThat(DeterministicContent.sha256Of(adapter.get(key))).isEqualTo(content.sha256());
    }

    @Test
    void a300MbObjectIsNotEligibleAndNothingIsRead() {
        InputStream neverRead = new InputStream() {
            @Override
            public int read() {
                throw new AssertionError("the content must not be read when the object is not eligible");
            }
        };
        String key = StorageKeys.forBenchmark(Instant.now(), UUID.randomUUID());

        assertThatThrownBy(() -> adapter.put(key, neverRead, 300L * 1024 * 1024, Map.of()))
                .isInstanceOf(StorageEligibilityException.class)
                .hasMessageContaining("exceeds");
        assertThat(adapter.exists(key)).isFalse();
    }

    @Test
    void a65MbObjectJustOverTheLimitIsRejectedWithoutBeingRead() {
        java.util.concurrent.atomic.AtomicLong bytesRead = new java.util.concurrent.atomic.AtomicLong();
        DeterministicContent content = new DeterministicContent(26, 65L * 1024 * 1024);
        InputStream counting = new java.io.FilterInputStream(content.open()) {
            @Override
            public int read() throws java.io.IOException {
                bytesRead.incrementAndGet();
                return super.read();
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws java.io.IOException {
                int read = super.read(buffer, offset, length);
                if (read > 0) {
                    bytesRead.addAndGet(read);
                }
                return read;
            }
        };
        String key = StorageKeys.forBenchmark(Instant.now(), UUID.randomUUID());

        assertThatThrownBy(() -> adapter.put(key, counting, content.size(), Map.of()))
                .isInstanceOf(StorageEligibilityException.class)
                .hasMessageContaining(String.valueOf(LIMIT));
        assertThat(bytesRead).hasValue(0);
        assertThat(adapter.exists(key)).isFalse();
    }

    @Test
    void anObjectOfExactlyTheLimitIsAccepted() {
        DeterministicContent content = new DeterministicContent(27, LIMIT);
        String key = StorageKeys.forBenchmark(Instant.now(), UUID.randomUUID());

        StoredObject stored = adapter.put(key, content, content.size(), Map.of());

        assertThat(stored.sha256()).isEqualTo(content.sha256());
        adapter.delete(key);
    }

    @Test
    void anUnknownSizeIsNotEligible() {
        String key = StorageKeys.forBenchmark(Instant.now(), UUID.randomUUID());

        assertThatThrownBy(() -> adapter.put(key, new ByteArrayInputStream(new byte[10]), -1, Map.of()))
                .isInstanceOf(StorageEligibilityException.class);
    }

    @Test
    void aFailedWriteLeavesNoRow() {
        String key = StorageKeys.forBenchmark(Instant.now(), UUID.randomUUID());

        // The stream is shorter than the declared size: the driver fails and the transaction rolls back.
        assertThatThrownBy(() -> adapter.put(key, new ByteArrayInputStream(new byte[100]), 200, Map.of()))
                .isInstanceOf(StorageException.class);
        assertThat(adapter.exists(key)).isFalse();
    }

    @Test
    void puttingTheSameKeyAgainReplacesTheObject() {
        String key = StorageKeys.forBenchmark(Instant.now(), UUID.randomUUID());
        DeterministicContent first = new DeterministicContent(23, 5_000);
        DeterministicContent second = new DeterministicContent(24, 7_000);

        adapter.put(key, first, first.size(), Map.of());
        adapter.put(key, second, second.size(), Map.of());

        assertThat(DeterministicContent.sha256Of(adapter.get(key))).isEqualTo(second.sha256());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stored_blob WHERE key = ?", Integer.class, key))
                .isEqualTo(1);
    }

    @Test
    void existsDeleteAndNotFound() {
        DeterministicContent content = new DeterministicContent(25, 1024);
        String key = StorageKeys.forBenchmark(Instant.now(), UUID.randomUUID());

        assertThat(adapter.exists(key)).isFalse();
        assertThatThrownBy(() -> adapter.get(key)).isInstanceOf(StorageObjectNotFoundException.class);

        adapter.put(key, content, content.size(), Map.of());
        assertThat(adapter.exists(key)).isTrue();

        adapter.delete(key);
        assertThat(adapter.exists(key)).isFalse();
        adapter.delete(key);
    }

    @Test
    void healthIsUp() {
        assertThat(adapter.health().up()).isTrue();
        assertThat(adapter.maxObjectBytes()).isEqualTo(LIMIT);
    }
}
