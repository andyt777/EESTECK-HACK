package ro.filegrid.crypto;

import com.google.crypto.tink.Aead;
import com.google.crypto.tink.KeyTemplates;
import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.StreamingAead;
import com.google.crypto.tink.TinkProtoKeysetFormat;
import com.google.crypto.tink.streamingaead.StreamingAeadConfig;
import com.google.crypto.tink.subtle.AesGcmJce;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import ro.filegrid.storage.Sha256;

/**
 * Application-level envelope encryption (ARCHITECTURE A.3.8): each file gets
 * its own data key (Tink Streaming AEAD, AES256_GCM_HKDF_1MB); the data key is
 * wrapped with the key-encryption key from {@code FILEGRID_KEK} and saved in
 * meta-db ({@code file_key}), never next to the object. The file id is the
 * associated data, so a ciphertext cannot be swapped between files. Several
 * KEKs can be configured (rotation): new files use the current one, old files
 * are opened with the key id they were wrapped with.
 */
@Component
public class EnvelopeEncryption {

    public static final String ALGORITHM = "AES256_GCM_HKDF_1MB";
    private static final int BUFFER = 1024 * 1024;

    /** What was written: hash and size of the ciphertext. */
    public record Encrypted(Path path, String sha256, long size, String keyId) {
    }

    private final JdbcTemplate metaDb;
    private final String currentKeyId;
    private final Map<String, Aead> keks = new LinkedHashMap<>();

    public EnvelopeEncryption(JdbcTemplate metaDb,
                              @Value("${filegrid.crypto.kek:}") String kek,
                              @Value("${filegrid.crypto.kek-id:kek-1}") String kekId,
                              @Value("${filegrid.crypto.previous-keks:}") String previous) throws GeneralSecurityException {
        StreamingAeadConfig.register();
        this.metaDb = metaDb;
        this.currentKeyId = kekId;
        if (kek != null && !kek.isBlank()) {
            keks.put(kekId, aead(kek));
        }
        // FILEGRID_KEK_PREVIOUS=id1:base64,id2:base64 — older KEKs still needed to open old files.
        if (previous != null && !previous.isBlank()) {
            for (String entry : previous.split(",")) {
                int colon = entry.indexOf(':');
                if (colon > 0) {
                    keks.putIfAbsent(entry.substring(0, colon).trim(), aead(entry.substring(colon + 1).trim()));
                }
            }
        }
    }

    private static Aead aead(String base64) throws GeneralSecurityException {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException e) {
            throw new GeneralSecurityException("FILEGRID_KEK is not valid base64");
        }
        if (raw.length != 32) {
            throw new GeneralSecurityException("FILEGRID_KEK must be 256 bit (32 bytes, base64)");
        }
        return new AesGcmJce(raw);
    }

    public boolean configured() {
        return keks.containsKey(currentKeyId);
    }

    /**
     * Encrypts {@code plain} into {@code target} (through a temporary file and
     * an atomic rename) with a new data key, and records the wrapped key.
     */
    public Encrypted encryptFile(UUID fileId, Path plain, Path target) throws IOException, GeneralSecurityException {
        Aead kek = keks.get(currentKeyId);
        if (kek == null) {
            throw new GeneralSecurityException("No key-encryption key configured (FILEGRID_KEK)");
        }
        KeysetHandle dek = KeysetHandle.generateNew(KeyTemplates.get(ALGORITHM));
        @SuppressWarnings("deprecation")
        StreamingAead streaming = dek.getPrimitive(StreamingAead.class);
        byte[] wrapped = TinkProtoKeysetFormat.serializeEncryptedKeyset(dek, kek, keyAad(fileId));
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        MessageDigest digest = Sha256.newDigest();
        byte[] buffer = new byte[BUFFER];
        try (InputStream in = Files.newInputStream(plain);
             DigestOutputStream hashed = new DigestOutputStream(Files.newOutputStream(temp), digest);
             OutputStream out = streaming.newEncryptingStream(hashed, aad(fileId))) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
        metaDb.update("""
                INSERT INTO file_key (file_id, key_id, algorithm, wrapped_dek) VALUES (?, ?, ?, ?)
                ON CONFLICT (file_id) DO UPDATE SET key_id = EXCLUDED.key_id, algorithm = EXCLUDED.algorithm,
                       wrapped_dek = EXCLUDED.wrapped_dek, created_at = now()
                """, fileId, currentKeyId, ALGORITHM, wrapped);
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return new Encrypted(target, Sha256.hex(digest), Files.size(target), currentKeyId);
    }

    /**
     * Plaintext view of a stored ciphertext. A ciphertext altered in the storage
     * fails authentication: reading then throws {@link CiphertextTamperedException}.
     */
    public InputStream decrypting(UUID fileId, InputStream ciphertext) throws IOException {
        List<Map<String, Object>> rows = metaDb.queryForList(
                "SELECT key_id, wrapped_dek FROM file_key WHERE file_id = ?", fileId);
        if (rows.isEmpty()) {
            ciphertext.close();
            throw new CiphertextTamperedException("No data key recorded for file " + fileId);
        }
        String keyId = (String) rows.get(0).get("key_id");
        Aead kek = keks.get(keyId);
        if (kek == null) {
            ciphertext.close();
            throw new IOException("Key-encryption key " + keyId + " is not configured; the file cannot be opened");
        }
        try {
            KeysetHandle dek = TinkProtoKeysetFormat.parseEncryptedKeyset((byte[]) rows.get(0).get("wrapped_dek"),
                    kek, keyAad(fileId));
            @SuppressWarnings("deprecation")
            StreamingAead streaming = dek.getPrimitive(StreamingAead.class);
            InputStream raw = new RawGuard(ciphertext);
            return new TamperGuard(streaming.newDecryptingStream(raw, aad(fileId)));
        } catch (GeneralSecurityException e) {
            ciphertext.close();
            throw new IOException("Data key of file " + fileId + " cannot be unwrapped with " + keyId
                    + " (wrong FILEGRID_KEK?)", e);
        }
    }

    private static byte[] aad(UUID fileId) {
        return ("filegrid:file:" + fileId).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] keyAad(UUID fileId) {
        return ("filegrid:dek:" + fileId).getBytes(StandardCharsets.UTF_8);
    }

    /** Thrown when the stored bytes are not an authentic ciphertext of the file. */
    public static class CiphertextTamperedException extends IOException {
        public CiphertextTamperedException(String message) {
            super(message);
        }
    }

    /** Marks I/O errors of the storage itself, so they are not mistaken for tampering. */
    static final class StorageReadException extends IOException {
        StorageReadException(IOException cause) {
            super(cause.getMessage(), cause);
        }
    }

    private static final class RawGuard extends FilterInputStream {
        RawGuard(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            try {
                return super.read();
            } catch (IOException e) {
                throw new StorageReadException(e);
            }
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            try {
                return super.read(b, off, len);
            } catch (IOException e) {
                throw new StorageReadException(e);
            }
        }
    }

    private static final class TamperGuard extends FilterInputStream {
        TamperGuard(InputStream in) {
            super(in);
        }

        private static IOException classify(IOException e) {
            for (Throwable t = e; t != null; t = t.getCause()) {
                if (t instanceof StorageReadException) {
                    return e;
                }
            }
            return new CiphertextTamperedException("Ciphertext authentication failed: " + e.getMessage());
        }

        @Override
        public int read() throws IOException {
            try {
                return super.read();
            } catch (IOException e) {
                throw classify(e);
            }
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            try {
                return super.read(b, off, len);
            } catch (IOException e) {
                throw classify(e);
            }
        }

        @Override
        public long skip(long n) throws IOException {
            try {
                return super.skip(n);
            } catch (IOException e) {
                throw classify(e);
            }
        }
    }
}
