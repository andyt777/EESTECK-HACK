package ro.filegrid.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ro.filegrid.file.FileCategory;
import ro.filegrid.storage.s3.S3ClientConfig;
import ro.filegrid.storage.s3.S3StorageAdapter;
import ro.filegrid.testsupport.DeterministicContent;
import ro.filegrid.testsupport.TestImages;
import software.amazon.awssdk.services.s3.S3Client;

@Testcontainers
class S3StorageAdapterIT {

    private static final int PART_SIZE = 16 * 1024 * 1024;

    @Container
    static final MinIOContainer S3 = new MinIOContainer(TestImages.S3);

    static S3Client client;
    static S3StorageAdapter adapter;

    @BeforeAll
    static void connect() {
        StorageProperties.S3 s3 = new StorageProperties.S3(URI.create(S3.getS3URL()), "us-east-1",
                S3.getUserName(), S3.getPassword(), "filegrid-", "filegrid-benchmark", PART_SIZE);
        client = S3ClientConfig.create(s3);
        for (String bucket : new String[] {"filegrid-benchmark", "filegrid-videos", "filegrid-documents"}) {
            client.createBucket(b -> b.bucket(bucket));
        }
        adapter = new S3StorageAdapter(client, new StorageProperties(null, s3, null, null));
    }

    @AfterAll
    static void disconnect() {
        client.close();
    }

    @Test
    void roundTripsA300MbObjectWithMultipartAndASmallHeap() {
        DeterministicContent content = new DeterministicContent(1, 300L * 1024 * 1024);
        String key = StorageKeys.forCategory(FileCategory.DRONE_VIDEO, Instant.now(), UUID.randomUUID());

        StoredObject stored;
        try (InputStream in = content.open()) {
            stored = adapter.put(key, in, content.size(), Map.of("category", "DRONE_VIDEO"));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }

        String expected = content.sha256();
        assertThat(stored.sizeWritten()).isEqualTo(content.size());
        assertThat(stored.sha256()).isEqualTo(expected);
        assertThat(DeterministicContent.sha256Of(adapter.get(key))).isEqualTo(expected);
    }

    @Test
    void roundTripsASmallObjectWithASinglePut() {
        DeterministicContent content = new DeterministicContent(2, 150_000);
        String key = StorageKeys.forCategory(FileCategory.CONNECTION_DOCUMENT, Instant.now(), UUID.randomUUID());

        StoredObject stored = adapter.put(key, content, content.size(), Map.of());

        assertThat(stored.sha256()).isEqualTo(content.sha256());
        assertThat(DeterministicContent.sha256Of(adapter.get(key))).isEqualTo(content.sha256());
    }

    @Test
    void roundTripsAnObjectOfExactlyOnePart() {
        DeterministicContent content = new DeterministicContent(3, PART_SIZE);
        String key = StorageKeys.forBenchmark(Instant.now(), UUID.randomUUID());

        StoredObject stored = adapter.put(key, content, content.size(), Map.of());

        assertThat(stored.sizeWritten()).isEqualTo(PART_SIZE);
        assertThat(DeterministicContent.sha256Of(adapter.get(key))).isEqualTo(content.sha256());
    }

    @Test
    void acceptsAnUnknownSize() {
        DeterministicContent content = new DeterministicContent(4, 20L * 1024 * 1024);
        String key = StorageKeys.forBenchmark(Instant.now(), UUID.randomUUID());

        StoredObject stored = adapter.put(key, content, -1, Map.of());

        assertThat(stored.sizeWritten()).isEqualTo(content.size());
        assertThat(DeterministicContent.sha256Of(adapter.get(key))).isEqualTo(content.sha256());
    }

    @Test
    void rejectsAndRemovesAnObjectWhoseSizeDiffersFromTheDeclaredOne() {
        String key = StorageKeys.forBenchmark(Instant.now(), UUID.randomUUID());

        assertThatThrownBy(() -> adapter.put(key, new ByteArrayInputStream(new byte[100]), 200, Map.of()))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("expected");
        assertThat(adapter.exists(key)).isFalse();
    }

    @Test
    void existsDeleteAndNotFound() {
        DeterministicContent content = new DeterministicContent(5, 1024);
        String key = StorageKeys.forBenchmark(Instant.now(), UUID.randomUUID());

        assertThat(adapter.exists(key)).isFalse();
        assertThatThrownBy(() -> adapter.get(key)).isInstanceOf(StorageObjectNotFoundException.class);

        adapter.put(key, content, content.size(), Map.of());
        assertThat(adapter.exists(key)).isTrue();

        adapter.delete(key);
        assertThat(adapter.exists(key)).isFalse();
        adapter.delete(key); // deleting a missing key is not an error
    }

    @Test
    void healthIsUpWhenTheBucketAnswersAndDownWhenTheEndpointIsUnreachable() {
        assertThat(adapter.health().up()).isTrue();

        StorageProperties.S3 unreachable = new StorageProperties.S3(URI.create("http://127.0.0.1:1"), "us-east-1",
                "nobody", "nothing", "filegrid-", "filegrid-benchmark", PART_SIZE);
        try (S3Client dead = S3ClientConfig.create(unreachable)) {
            StorageHealthResult result =
                    new S3StorageAdapter(dead, new StorageProperties(null, unreachable, null, null)).health();
            assertThat(result.up()).isFalse();
            assertThat(result.detail()).isNotBlank();
        }
    }
}
