package ro.filegrid.storage.database;

import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import ro.filegrid.storage.Sha256;
import ro.filegrid.storage.StorageAdapter;
import ro.filegrid.storage.StorageEligibilityException;
import ro.filegrid.storage.StorageException;
import ro.filegrid.storage.StorageHealthResult;
import ro.filegrid.storage.StorageKeys;
import ro.filegrid.storage.StorageObjectNotFoundException;
import ro.filegrid.storage.StorageProperties;
import ro.filegrid.storage.StorageType;
import ro.filegrid.storage.StoredObject;

/**
 * "Database" storage: one bytea row per object in store-db.
 *
 * <p>Writes stream the content to the JDBC driver; reads fetch the object in
 * fixed-size slices, so neither direction holds the whole object in memory.
 * Objects above {@code max-object-bytes} (64 MB by default) are not eligible.
 */
@Component
public class DatabaseStorageAdapter implements StorageAdapter {

    private static final String UPSERT = """
            INSERT INTO stored_blob (key, content, size, sha256, created_at)
            VALUES (?, ?, ?, '', now())
            ON CONFLICT (key) DO UPDATE
                SET content = EXCLUDED.content, size = EXCLUDED.size, sha256 = '', created_at = now()
            """;
    private static final String SET_HASH = "UPDATE stored_blob SET sha256 = ? WHERE key = ?";

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final long maxObjectBytes;
    private final int readChunkBytes;

    public DatabaseStorageAdapter(@Qualifier(StoreDbConfig.QUALIFIER) DataSource dataSource,
                                  @Qualifier(StoreDbConfig.QUALIFIER) JdbcTemplate jdbc,
                                  StorageProperties properties) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
        this.maxObjectBytes = properties.database().maxObjectBytes();
        this.readChunkBytes = properties.database().readChunkBytes();
    }

    @Override
    public StorageType type() {
        return StorageType.DATABASE;
    }

    /** Largest object this storage accepts (used by routing to decide eligibility). */
    public long maxObjectBytes() {
        return maxObjectBytes;
    }

    @Override
    public StoredObject put(String key, InputStream content, long size, Map<String, String> metadata) {
        StorageKeys.requireValid(key);
        if (size < 0) {
            // The limit must be enforced before anything is written.
            throw new StorageEligibilityException("The Database storage needs the size in advance");
        }
        if (size > maxObjectBytes) {
            throw new StorageEligibilityException(
                    "Object of " + size + " bytes exceeds the Database storage limit of " + maxObjectBytes + " bytes");
        }

        MessageDigest digest = Sha256.newDigest();
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement insert = connection.prepareStatement(UPSERT)) {
                    insert.setString(1, key);
                    // With an explicit length the driver streams the parameter to the server.
                    insert.setBinaryStream(2, new DigestInputStream(content, digest), size);
                    insert.setLong(3, size);
                    insert.executeUpdate();
                }
                String sha256 = Sha256.hex(digest);
                try (PreparedStatement update = connection.prepareStatement(SET_HASH)) {
                    update.setString(1, sha256);
                    update.setString(2, key);
                    update.executeUpdate();
                }
                connection.commit();
                return new StoredObject(key, size, sha256);
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new StorageException("Database write failed for " + key + ": " + e.getMessage(), e);
        }
    }

    @Override
    public InputStream get(String key) {
        StorageKeys.requireValid(key);
        Long size;
        try {
            size = jdbc.query("SELECT size FROM stored_blob WHERE key = ?",
                    rs -> rs.next() ? rs.getLong(1) : null, key);
        } catch (DataAccessException e) {
            throw new StorageException("Database read failed for " + key + ": " + e.getMessage(), e);
        }
        if (size == null) {
            throw new StorageObjectNotFoundException(StorageType.DATABASE, key);
        }
        return new ChunkedBlobInputStream(key, size);
    }

    @Override
    public boolean exists(String key) {
        StorageKeys.requireValid(key);
        try {
            Boolean found = jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM stored_blob WHERE key = ?)", Boolean.class, key);
            return Boolean.TRUE.equals(found);
        } catch (DataAccessException e) {
            throw new StorageException("Database lookup failed for " + key + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void delete(String key) {
        StorageKeys.requireValid(key);
        try {
            jdbc.update("DELETE FROM stored_blob WHERE key = ?", key);
        } catch (DataAccessException e) {
            throw new StorageException("Database delete failed for " + key + ": " + e.getMessage(), e);
        }
    }

    @Override
    public StorageHealthResult health() {
        long start = System.nanoTime();
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            return StorageHealthResult.up(elapsedMs(start));
        } catch (RuntimeException e) {
            return StorageHealthResult.down(elapsedMs(start), e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /**
     * Reads a stored object slice by slice with {@code substring()}; only one
     * slice ({@code read-chunk-bytes}) is in memory at a time.
     */
    private final class ChunkedBlobInputStream extends InputStream {

        private final String key;
        private final long size;
        private long position;
        private byte[] chunk = new byte[0];
        private int chunkOffset;

        ChunkedBlobInputStream(String key, long size) {
            this.key = key;
            this.size = size;
        }

        @Override
        public int read() throws IOException {
            if (!ensureChunk()) {
                return -1;
            }
            return chunk[chunkOffset++] & 0xFF;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            if (!ensureChunk()) {
                return -1;
            }
            int count = Math.min(length, chunk.length - chunkOffset);
            System.arraycopy(chunk, chunkOffset, buffer, offset, count);
            chunkOffset += count;
            return count;
        }

        private boolean ensureChunk() throws IOException {
            if (chunkOffset < chunk.length) {
                return true;
            }
            if (position >= size) {
                return false;
            }
            int length = (int) Math.min(readChunkBytes, size - position);
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement select = connection.prepareStatement(
                         "SELECT substring(content FROM ? FOR ?) FROM stored_blob WHERE key = ?")) {
                // SQL positions start at 1. substring(bytea, int, int): the object
                // size is capped far below 2 GB, so the position fits an int.
                select.setInt(1, Math.toIntExact(position + 1));
                select.setInt(2, length);
                select.setString(3, key);
                try (ResultSet rs = select.executeQuery()) {
                    if (!rs.next()) {
                        throw new IOException("Object " + key + " disappeared while it was being read");
                    }
                    chunk = rs.getBytes(1);
                }
            } catch (SQLException e) {
                throw new IOException("Database read failed for " + key + ": " + e.getMessage(), e);
            }
            if (chunk == null || chunk.length == 0) {
                throw new IOException("Object " + key + " is shorter than its recorded size");
            }
            chunkOffset = 0;
            position += chunk.length;
            return true;
        }
    }
}
