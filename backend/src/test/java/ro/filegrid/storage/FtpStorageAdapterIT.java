package ro.filegrid.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.FixedHostPortGenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ro.filegrid.file.FileCategory;
import ro.filegrid.storage.ftp.FtpStorageAdapter;
import ro.filegrid.testsupport.DeterministicContent;
import ro.filegrid.testsupport.TestImages;

@Testcontainers
class FtpStorageAdapterIT {

    private static final String USER = "filegrid";
    private static final String SECRET = "it-only-" + UUID.randomUUID();
    private static final int PASSIVE_FROM = 31000;
    private static final int PASSIVE_TO = 31009;

    /**
     * Passive mode needs the data ports reachable on the host under the same
     * numbers the server announces, hence the fixed port mappings.
     */
    @Container
    @SuppressWarnings({"resource", "deprecation"})
    static final FixedHostPortGenericContainer<?> FTP_SERVER = passivePorts(
            new FixedHostPortGenericContainer<>(TestImages.FTP.asCanonicalNameString()))
            .withExposedPorts(21)
            .withEnv("PUBLICHOST", "127.0.0.1")
            .withEnv("FTP_PASSIVE_PORTS", PASSIVE_FROM + ":" + PASSIVE_TO)
            .withEnv("FTP_USER_NAME", USER)
            .withEnv("FTP_USER_PASS", SECRET)
            .withEnv("FTP_USER_HOME", "/home/ftpusers/filegrid")
            .waitingFor(Wait.forListeningPort());

    static FtpStorageAdapter adapter;

    private static FixedHostPortGenericContainer<?> passivePorts(FixedHostPortGenericContainer<?> container) {
        for (int port = PASSIVE_FROM; port <= PASSIVE_TO; port++) {
            container.withFixedExposedPort(port, port);
        }
        return container;
    }

    @BeforeAll
    static void connect() {
        adapter = new FtpStorageAdapter(new StorageProperties(null, null, ftp(FTP_SERVER.getMappedPort(21)), null));
    }

    @AfterAll
    static void disconnect() {
        adapter.close();
    }

    private static StorageProperties.Ftp ftp(int port) {
        return new StorageProperties.Ftp(FTP_SERVER.getHost(), port, USER, SECRET, 3,
                Duration.ofSeconds(5), Duration.ofSeconds(60), 3);
    }

    @Test
    void roundTripsA300MbFileWithASmallHeap() throws IOException {
        DeterministicContent content = new DeterministicContent(11, 300L * 1024 * 1024);
        String key = StorageKeys.forCategory(FileCategory.DRONE_VIDEO, Instant.now(), UUID.randomUUID());

        StoredObject stored;
        try (InputStream in = content.open()) {
            stored = adapter.put(key, in, content.size(), Map.of());
        }

        String expected = content.sha256();
        assertThat(stored.sizeWritten()).isEqualTo(content.size());
        assertThat(stored.sha256()).isEqualTo(expected);
        assertThat(DeterministicContent.sha256Of(adapter.get(key))).isEqualTo(expected);
    }

    @Test
    void storesUnderCategoryYearMonthDayAndLeavesNoPartFile() throws IOException {
        DeterministicContent content = new DeterministicContent(12, 2 * 1024 * 1024);
        String key = StorageKeys.forCategory(FileCategory.FAULT_RECORD,
                Instant.parse("2026-03-09T10:00:00Z"), UUID.randomUUID());

        adapter.put(key, content, content.size(), Map.of());

        assertThat(key).startsWith("faults/2026/03/09/");
        FTPClient raw = rawClient();
        try {
            // The server also lists "." and ".."; only real entries matter.
            assertThat(raw.listNames("faults/2026/03/09"))
                    .filteredOn(name -> !name.endsWith("/.") && !name.endsWith("/.."))
                    .containsExactly(key);
        } finally {
            raw.disconnect();
        }
    }

    /**
     * The partial file left on the server is deliberately NOT the real beginning
     * of the content (it is zeros). If the adapter resumes with REST, the final
     * file is "zeros + second half"; if it restarted, it would be the full content.
     */
    @Test
    void resumesAnInterruptedUploadFromTheBytesAlreadyOnTheServer() throws IOException {
        DeterministicContent content = new DeterministicContent(13, 8L * 1024 * 1024);
        int alreadyOnServer = 3 * 1024 * 1024;
        String key = StorageKeys.forCategory(FileCategory.DRONE_VIDEO, Instant.now(), UUID.randomUUID());

        FTPClient raw = rawClient();
        try {
            for (String directory : new String[] {"videos", directoryOf(key, 2), directoryOf(key, 3), directoryOf(key, 4)}) {
                raw.makeDirectory(directory);
            }
            assertThat(raw.storeFile(key + ".part", new ByteArrayInputStream(new byte[alreadyOnServer]))).isTrue();
        } finally {
            raw.disconnect();
        }

        StoredObject stored = adapter.put(key, content, content.size(), Map.of());

        // The returned hash always describes the source content...
        assertThat(stored.sizeWritten()).isEqualTo(content.size());
        assertThat(stored.sha256()).isEqualTo(content.sha256());
        // ...while the file on the server proves that only the remaining bytes were sent.
        InputStream tail = content.open();
        tail.skipNBytes(alreadyOnServer);
        String zerosThenTail = DeterministicContent.sha256Of(
                new SequenceInputStream(new ByteArrayInputStream(new byte[alreadyOnServer]), tail));
        assertThat(DeterministicContent.sha256Of(adapter.get(key))).isEqualTo(zerosThenTail);
    }

    /**
     * A real interruption: the first read of the source breaks at 50%. The
     * adapter must reopen the source, continue from what the server already
     * has, and report the hash of the WHOLE file, identical to the original.
     */
    @Test
    void anUploadInterruptedAtHalfIsResumedAndTheHashCoversTheWholeFile() {
        DeterministicContent content = new DeterministicContent(16, 40L * 1024 * 1024);
        long breakAt = content.size() / 2;
        java.util.concurrent.atomic.AtomicInteger opens = new java.util.concurrent.atomic.AtomicInteger();
        StreamSource flaky = () -> {
            if (opens.incrementAndGet() > 1) {
                return content.open();
            }
            return new java.io.FilterInputStream(content.open()) {
                private long position;

                @Override
                public int read(byte[] buffer, int offset, int length) throws IOException {
                    if (position >= breakAt) {
                        throw new IOException("simulated connection loss at 50%");
                    }
                    int read = super.read(buffer, offset, (int) Math.min(length, breakAt - position));
                    if (read > 0) {
                        position += read;
                    }
                    return read;
                }
            };
        };
        String key = StorageKeys.forCategory(FileCategory.DRONE_VIDEO, Instant.now(), UUID.randomUUID());

        StoredObject stored = adapter.put(key, flaky, content.size(), Map.of());

        String original = content.sha256();
        assertThat(opens).as("the source was reopened once after the interruption").hasValue(2);
        assertThat(stored.sizeWritten()).isEqualTo(content.size());
        assertThat(stored.sha256()).as("hash reported by the adapter").isEqualTo(original);
        assertThat(DeterministicContent.sha256Of(adapter.get(key)))
                .as("hash of the file on the server").isEqualTo(original);
    }

    @Test
    void anUploadThatKeepsFailingGivesUpAfterTheConfiguredAttempts() {
        java.util.concurrent.atomic.AtomicInteger opens = new java.util.concurrent.atomic.AtomicInteger();
        StreamSource alwaysBroken = () -> {
            opens.incrementAndGet();
            return new InputStream() {
                @Override
                public int read() throws IOException {
                    throw new IOException("simulated permanent failure");
                }
            };
        };
        String key = StorageKeys.forBenchmark(Instant.now(), UUID.randomUUID());

        assertThatThrownBy(() -> adapter.put(key, alwaysBroken, 1024, Map.of()))
                .isInstanceOf(StorageException.class);
        // 1 initial attempt + max-resume-attempts (3).
        assertThat(opens).hasValue(4);
        assertThat(adapter.exists(key)).isFalse();
    }

    @Test
    void aPartialFileLongerThanTheContentIsDiscarded() throws IOException {
        DeterministicContent content = new DeterministicContent(14, 1024 * 1024);
        String key = StorageKeys.forBenchmark(Instant.now(), UUID.randomUUID());
        FTPClient raw = rawClient();
        try {
            for (String directory : new String[] {"benchmark", directoryOf(key, 2), directoryOf(key, 3), directoryOf(key, 4)}) {
                raw.makeDirectory(directory);
            }
            raw.storeFile(key + ".part", new ByteArrayInputStream(new byte[2 * 1024 * 1024]));
        } finally {
            raw.disconnect();
        }

        adapter.put(key, content, content.size(), Map.of());

        assertThat(DeterministicContent.sha256Of(adapter.get(key))).isEqualTo(content.sha256());
    }

    @Test
    void existsDeleteAndNotFound() {
        DeterministicContent content = new DeterministicContent(15, 4096);
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
    void rejectsADeclaredSizeThatDoesNotMatch() {
        String key = StorageKeys.forBenchmark(Instant.now(), UUID.randomUUID());

        assertThatThrownBy(() -> adapter.put(key, new ByteArrayInputStream(new byte[100]), 200, Map.of()))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("expected");
        assertThat(adapter.exists(key)).isFalse();
    }

    @Test
    void rejectsPathTraversalBeforeTalkingToTheServer() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> adapter.put("faults/../../etc/passwd", new ByteArrayInputStream(new byte[1]), 1, Map.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> adapter.get("faults/../x"));
    }

    @Test
    void connectionsAreReusedAcrossManyOperations() {
        // More operations than the pool size (3) and than the server's per-IP limit.
        for (int i = 0; i < 12; i++) {
            DeterministicContent content = new DeterministicContent(100 + i, 10_000);
            String key = StorageKeys.forBenchmark(Instant.now(), UUID.randomUUID());
            adapter.put(key, content, content.size(), Map.of());
            assertThat(DeterministicContent.sha256Of(adapter.get(key))).isEqualTo(content.sha256());
        }
    }

    @Test
    void healthIsUpForTheServerAndDownForAClosedPort() {
        assertThat(adapter.health().up()).isTrue();

        FtpStorageAdapter dead = new FtpStorageAdapter(new StorageProperties(null, null,
                new StorageProperties.Ftp("127.0.0.1", 1, USER, SECRET, 1,
                        Duration.ofSeconds(2), Duration.ofSeconds(2), 0), null));
        try {
            StorageHealthResult result = dead.health();
            assertThat(result.up()).isFalse();
            assertThat(result.detail()).isNotBlank();
        } finally {
            dead.close();
        }
    }

    /** The first {@code levels} path segments of the key, for example {@code videos/2026/11}. */
    private static String directoryOf(String key, int levels) {
        String[] segments = key.split("/");
        return String.join("/", java.util.Arrays.copyOf(segments, levels));
    }

    private static FTPClient rawClient() throws IOException {
        FTPClient client = new FTPClient();
        client.connect(FTP_SERVER.getHost(), FTP_SERVER.getMappedPort(21));
        assertThat(client.login(USER, SECRET)).isTrue();
        client.setFileType(FTP.BINARY_FILE_TYPE);
        client.enterLocalPassiveMode();
        return client;
    }
}
