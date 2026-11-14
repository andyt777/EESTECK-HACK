package ro.filegrid.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ro.filegrid.storage.Sha256;
import ro.filegrid.testsupport.TestImages;

/** Envelope encryption: 10 MB round trip, wrong KEK, altered ciphertext, KEK rotation. */
@Testcontainers
class EnvelopeEncryptionIT {

    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>(TestImages.POSTGRES);

    static JdbcTemplate jdbc;
    static final String KEK_A = Base64.getEncoder().encodeToString("A".repeat(32).getBytes());
    static final String KEK_B = Base64.getEncoder().encodeToString("B".repeat(32).getBytes());

    @TempDir
    Path dir;

    @BeforeAll
    static void schema() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword()));
        jdbc.execute("""
                CREATE TABLE file_key (file_id UUID PRIMARY KEY, key_id VARCHAR(64) NOT NULL,
                  algorithm VARCHAR(64) NOT NULL, wrapped_dek BYTEA NOT NULL, created_at TIMESTAMPTZ DEFAULT now())
                """);
    }

    private Path plain(int size) throws IOException {
        Path p = dir.resolve("plain");
        byte[] chunk = new byte[1024 * 1024];
        Random random = new Random(42);
        try (OutputStream out = Files.newOutputStream(p)) {
            for (int written = 0; written < size; written += chunk.length) {
                random.nextBytes(chunk);
                out.write(chunk, 0, Math.min(chunk.length, size - written));
            }
        }
        return p;
    }

    private static String sha(InputStream in) throws IOException {
        MessageDigest d = Sha256.newDigest();
        byte[] buf = new byte[64 * 1024];
        int r;
        while ((r = in.read(buf)) != -1) {
            d.update(buf, 0, r);
        }
        return Sha256.hex(d);
    }

    @Test
    void roundTripOf10MbAndCiphertextIsUnreadable() throws Exception {
        EnvelopeEncryption crypto = new EnvelopeEncryption(jdbc, KEK_A, "kek-a", "");
        UUID id = UUID.randomUUID();
        Path plain = plain(10 * 1024 * 1024);
        EnvelopeEncryption.Encrypted enc = crypto.encryptFile(id, plain, dir.resolve("x.enc"));
        String plainSha;
        try (InputStream in = Files.newInputStream(plain)) {
            plainSha = sha(in);
        }
        try (InputStream in = Files.newInputStream(enc.path())) {
            assertThat(sha(in)).isEqualTo(enc.sha256()).isNotEqualTo(plainSha);
        }
        try (InputStream in = crypto.decrypting(id, Files.newInputStream(enc.path()))) {
            assertThat(sha(in)).isEqualTo(plainSha);
        }
    }

    @Test
    void wrongKekFailsClearly() throws Exception {
        UUID id = UUID.randomUUID();
        Path enc = new EnvelopeEncryption(jdbc, KEK_A, "kek-a", "")
                .encryptFile(id, plain(100_000), dir.resolve("y.enc")).path();
        EnvelopeEncryption wrong = new EnvelopeEncryption(jdbc, KEK_B, "kek-a", "");
        assertThatThrownBy(() -> wrong.decrypting(id, Files.newInputStream(enc)))
                .isInstanceOf(IOException.class).hasMessageContaining("wrong FILEGRID_KEK");
    }

    @Test
    void alteredCiphertextIsDetected() throws Exception {
        EnvelopeEncryption crypto = new EnvelopeEncryption(jdbc, KEK_A, "kek-a", "");
        UUID id = UUID.randomUUID();
        Path enc = crypto.encryptFile(id, plain(3 * 1024 * 1024), dir.resolve("z.enc")).path();
        byte[] all = Files.readAllBytes(enc); // test-only: 3 MB
        all[all.length / 2] ^= 0x01;
        Files.write(enc, all);
        assertThatThrownBy(() -> {
            try (InputStream in = crypto.decrypting(id, Files.newInputStream(enc))) {
                sha(in);
            }
        }).isInstanceOf(EnvelopeEncryption.CiphertextTamperedException.class);
    }

    @Test
    void rotatedKekStillOpensOldFiles() throws Exception {
        UUID id = UUID.randomUUID();
        Path plain = plain(200_000);
        Path enc = new EnvelopeEncryption(jdbc, KEK_A, "kek-a", "").encryptFile(id, plain, dir.resolve("r.enc"))
                .path();
        EnvelopeEncryption rotated = new EnvelopeEncryption(jdbc, KEK_B, "kek-b", "kek-a:" + KEK_A);
        try (InputStream in = rotated.decrypting(id, Files.newInputStream(enc));
             InputStream expected = Files.newInputStream(plain)) {
            assertThat(sha(in)).isEqualTo(sha(expected));
        }
    }
}
