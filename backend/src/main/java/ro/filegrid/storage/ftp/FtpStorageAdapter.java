package ro.filegrid.storage.ftp;

import jakarta.annotation.PreDestroy;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ro.filegrid.storage.Sha256;
import ro.filegrid.storage.StorageAdapter;
import ro.filegrid.storage.StorageException;
import ro.filegrid.storage.StorageHealthResult;
import ro.filegrid.storage.StorageKeys;
import ro.filegrid.storage.StorageObjectNotFoundException;
import ro.filegrid.storage.StorageProperties;
import ro.filegrid.storage.StorageType;
import ro.filegrid.storage.StoredObject;
import ro.filegrid.storage.StreamSource;

/**
 * FTP storage (binary, passive mode, pooled connections).
 *
 * <p>An upload goes to {@code <key>.part} and is renamed to its final name only
 * when complete, so a reader never sees a half-written file. When the content
 * can be reopened, an interrupted upload is resumed from the bytes already on
 * the server (FTP {@code REST} offset) instead of starting over.
 */
@Component
public class FtpStorageAdapter implements StorageAdapter {

    private static final Logger log = LoggerFactory.getLogger(FtpStorageAdapter.class);
    private static final String PART_SUFFIX = ".part";
    private static final Duration BORROW_TIMEOUT = Duration.ofSeconds(30);

    private final GenericObjectPool<FTPClient> pool;
    private final int maxResumeAttempts;
    /** Directories known to exist, to avoid sending MKD for every file. */
    private final Set<String> knownDirectories = ConcurrentHashMap.newKeySet();

    public FtpStorageAdapter(StorageProperties properties) {
        StorageProperties.Ftp ftp = properties.ftp();
        GenericObjectPoolConfig<FTPClient> config = new GenericObjectPoolConfig<>();
        config.setMaxTotal(ftp.poolSize());
        config.setMaxIdle(ftp.poolSize());
        config.setMinIdle(0);
        config.setMaxWait(BORROW_TIMEOUT);
        // Reconnect transparently: a dead connection is detected and replaced on borrow.
        config.setTestOnBorrow(true);
        config.setTimeBetweenEvictionRuns(Duration.ofSeconds(30));
        config.setMinEvictableIdleDuration(Duration.ofMinutes(2));
        config.setJmxEnabled(false);
        this.pool = new GenericObjectPool<>(new FtpClientFactory(ftp), config);
        this.maxResumeAttempts = ftp.maxResumeAttempts();
    }

    @Override
    public StorageType type() {
        return StorageType.FTP;
    }

    @Override
    public StoredObject put(String key, InputStream content, long size, Map<String, String> metadata) {
        StorageKeys.requireValid(key);
        return execute("write", key, client -> {
            // The content cannot be reopened, so a leftover partial file is useless.
            client.deleteFile(key + PART_SUFFIX);
            MessageDigest digest = Sha256.newDigest();
            long written = upload(client, key, new DigestInputStream(content, digest), 0);
            return finish(client, key, written, size, digest);
        });
    }

    @Override
    public StoredObject put(String key, StreamSource source, long size, Map<String, String> metadata) {
        StorageKeys.requireValid(key);
        StorageException lastFailure = null;
        for (int attempt = 0; attempt <= maxResumeAttempts; attempt++) {
            try {
                return execute("write", key, client -> resumeOrStart(client, key, source, size));
            } catch (StorageException e) {
                lastFailure = e;
                log.warn("FTP upload of {} interrupted (attempt {} of {}): {}",
                        key, attempt + 1, maxResumeAttempts + 1, e.getMessage());
            }
        }
        throw lastFailure;
    }

    private StoredObject resumeOrStart(FTPClient client, String key, StreamSource source, long size)
            throws IOException {
        String partName = key + PART_SUFFIX;
        long alreadyStored = remoteSize(client, partName);
        if (alreadyStored > 0 && size >= 0 && alreadyStored > size) {
            // Longer than the whole object: not a partial copy of this content.
            client.deleteFile(partName);
            alreadyStored = -1;
        }
        long offset = Math.max(alreadyStored, 0);

        MessageDigest digest = Sha256.newDigest();
        try (InputStream raw = source.open()) {
            DigestInputStream in = new DigestInputStream(raw, digest);
            // The hash must cover the whole object, so the part that is already on
            // the server is read (and hashed) locally instead of being sent again.
            skipFully(in, offset, key);
            if (offset > 0) {
                log.info("Resuming FTP upload of {} from byte {}", key, offset);
            }
            long written = offset + upload(client, key, in, offset);
            return finish(client, key, written, size, digest);
        }
    }

    /** Sends the stream to {@code <key>.part} starting at the given offset; returns the bytes sent. */
    private long upload(FTPClient client, String key, InputStream content, long offset) throws IOException {
        ensureDirectories(client, key);
        CountingInputStream counting = new CountingInputStream(content);
        try {
            client.setRestartOffset(offset);
            if (!client.storeFile(key + PART_SUFFIX, counting)) {
                throw new IOException("FTP STOR failed: " + client.getReplyString().trim());
            }
        } finally {
            client.setRestartOffset(0);
        }
        return counting.count();
    }

    private StoredObject finish(FTPClient client, String key, long written, long size, MessageDigest digest)
            throws IOException {
        String partName = key + PART_SUFFIX;
        if (size >= 0 && written != size) {
            client.deleteFile(partName);
            throw new IOException("FTP wrote " + written + " bytes but " + size + " were expected");
        }
        // RNFR/RNTO is atomic on the server: the final name appears complete or not at all.
        if (!client.rename(partName, key)) {
            throw new IOException("FTP rename to the final name failed: " + client.getReplyString().trim());
        }
        return new StoredObject(key, written, Sha256.hex(digest));
    }

    @Override
    public InputStream get(String key) {
        StorageKeys.requireValid(key);
        FTPClient client = borrow();
        try {
            InputStream data = client.retrieveFileStream(key);
            if (data == null) {
                int reply = client.getReplyCode();
                String message = client.getReplyString().trim();
                pool.returnObject(client);
                if (reply == FTPReply.FILE_UNAVAILABLE) {
                    throw new StorageObjectNotFoundException(StorageType.FTP, key);
                }
                throw new StorageException("FTP read failed for " + key + ": " + message);
            }
            return new PooledDownloadStream(data, client);
        } catch (IOException e) {
            invalidate(client);
            throw new StorageException("FTP read failed for " + key + ": " + e.getMessage(), e);
        }
    }

    @Override
    public boolean exists(String key) {
        StorageKeys.requireValid(key);
        return execute("lookup", key, client -> remoteSize(client, key) >= 0);
    }

    @Override
    public void delete(String key) {
        StorageKeys.requireValid(key);
        execute("delete", key, client -> {
            client.deleteFile(key);
            client.deleteFile(key + PART_SUFFIX);
            return null;
        });
    }

    @Override
    public StorageHealthResult health() {
        long start = System.nanoTime();
        try {
            boolean ok = execute("health check", "-", FTPClient::sendNoOp);
            long latency = elapsedMs(start);
            return ok ? StorageHealthResult.up(latency) : StorageHealthResult.down(latency, "NOOP rejected");
        } catch (RuntimeException e) {
            return StorageHealthResult.down(elapsedMs(start), e.getMessage());
        }
    }

    @PreDestroy
    public void close() {
        pool.close();
    }

    /** Size of a remote file, or -1 when it does not exist. */
    private static long remoteSize(FTPClient client, String path) throws IOException {
        int reply = client.sendCommand("SIZE", path);
        if (reply != FTPReply.FILE_STATUS) {
            return -1;
        }
        String text = client.getReplyString().trim();
        try {
            return Long.parseLong(text.substring(text.lastIndexOf(' ') + 1));
        } catch (NumberFormatException e) {
            throw new IOException("Unexpected SIZE reply: " + text);
        }
    }

    /** Creates {@code category/yyyy/MM/dd} for the key, one level at a time. */
    private void ensureDirectories(FTPClient client, String key) throws IOException {
        String directory = key.substring(0, key.lastIndexOf('/'));
        if (knownDirectories.contains(directory)) {
            return;
        }
        int slash = 0;
        while (slash >= 0) {
            slash = directory.indexOf('/', slash + 1);
            String level = slash < 0 ? directory : directory.substring(0, slash);
            // MKD fails when the directory already exists; that is the normal case.
            client.makeDirectory(level);
        }
        knownDirectories.add(directory);
    }

    private static void skipFully(InputStream in, long bytes, String key) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long remaining = bytes;
        while (remaining > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) {
                throw new IOException("Source of " + key + " is shorter than the partial file on the server");
            }
            remaining -= read;
        }
    }

    private <T> T execute(String operation, String key, FtpOperation<T> action) {
        FTPClient client = borrow();
        try {
            T result = action.run(client);
            pool.returnObject(client);
            return result;
        } catch (IOException e) {
            // The connection state is unknown after an I/O error: never reuse it.
            invalidate(client);
            throw new StorageException("FTP " + operation + " failed for " + key + ": " + e.getMessage(), e);
        } catch (RuntimeException e) {
            invalidate(client);
            throw e;
        }
    }

    private FTPClient borrow() {
        try {
            return pool.borrowObject();
        } catch (Exception e) {
            throw new StorageException("Cannot get an FTP connection: " + e.getMessage(), e);
        }
    }

    private void invalidate(FTPClient client) {
        try {
            pool.invalidateObject(client);
        } catch (Exception e) {
            log.debug("Could not discard an FTP connection: {}", e.getMessage());
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    @FunctionalInterface
    private interface FtpOperation<T> {
        T run(FTPClient client) throws IOException;
    }

    /** Counts the bytes read through it. */
    private static final class CountingInputStream extends FilterInputStream {

        private long count;

        CountingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                count++;
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) {
                count += read;
            }
            return read;
        }

        long count() {
            return count;
        }
    }

    /**
     * Download stream that owns its FTP connection: closing it finishes the
     * transfer and gives the connection back to the pool.
     */
    private final class PooledDownloadStream extends FilterInputStream {

        private final FTPClient client;
        private boolean closed;

        PooledDownloadStream(InputStream in, FTPClient client) {
            super(in);
            this.client = client;
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            try {
                super.close();
                if (client.completePendingCommand()) {
                    pool.returnObject(client);
                } else {
                    // Typically the reader stopped before the end of the file.
                    invalidate(client);
                }
            } catch (IOException e) {
                invalidate(client);
                throw e;
            }
        }
    }
}
