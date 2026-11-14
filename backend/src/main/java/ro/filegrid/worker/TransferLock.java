package ro.filegrid.worker;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.stereotype.Component;

/**
 * Makes sure a file is transferred by one worker at a time, across every
 * worker replica. It is a PostgreSQL session advisory lock held on a dedicated
 * connection for the duration of the transfer: if the worker dies, the
 * connection drops and the lock is released at once, so the job that RabbitMQ
 * redelivers can be taken over immediately (no lease to wait out).
 *
 * <p>The connection is opened outside the pool on purpose: a long video must
 * never hold one of the few pooled connections the worker needs for its updates.
 */
@Component
public class TransferLock {

    private static final Logger log = LoggerFactory.getLogger(TransferLock.class);

    private final DataSourceProperties dataSource;

    public TransferLock(DataSourceProperties dataSource) {
        this.dataSource = dataSource;
    }

    /** Tries to take the lock; empty when another worker holds it. */
    public Optional<Held> tryAcquire(UUID fileId) {
        Connection connection = null;
        try {
            connection = DriverManager.getConnection(dataSource.determineUrl(), dataSource.determineUsername(),
                    dataSource.determinePassword());
            try (PreparedStatement lock = connection.prepareStatement("SELECT pg_try_advisory_lock(?, ?)")) {
                lock.setInt(1, (int) (fileId.getMostSignificantBits() >>> 32));
                lock.setInt(2, (int) fileId.getLeastSignificantBits());
                try (ResultSet rs = lock.executeQuery()) {
                    if (rs.next() && rs.getBoolean(1)) {
                        return Optional.of(new Held(connection, fileId));
                    }
                }
            }
            connection.close();
            return Optional.empty();
        } catch (SQLException e) {
            closeQuietly(connection);
            throw new IllegalStateException("Cannot take the transfer lock of file " + fileId + ": " + e.getMessage(), e);
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // nothing to do
            }
        }
    }

    /** A held lock; closing it releases the lock (closing the connection releases it too). */
    public static final class Held implements AutoCloseable {
        private final Connection connection;
        private final UUID fileId;

        private Held(Connection connection, UUID fileId) {
            this.connection = connection;
            this.fileId = fileId;
        }

        @Override
        public void close() {
            try (connection) {
                try (PreparedStatement unlock = connection.prepareStatement("SELECT pg_advisory_unlock_all()")) {
                    unlock.execute();
                }
            } catch (SQLException e) {
                log.debug("Transfer lock of {} released by closing the connection: {}", fileId, e.getMessage());
            }
        }
    }
}
