package ro.filegrid.storage.database;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Connection to store-db, the separate PostgreSQL instance that acts as the
 * "Database" storage. It is deliberately not a default autowiring candidate,
 * so the auto-configured DataSource (meta-db) stays the one used by JPA and
 * by the main Flyway migrations.
 */
@Configuration
public class StoreDbConfig {

    public static final String QUALIFIER = "storeDb";

    @Bean(defaultCandidate = false)
    @Qualifier(QUALIFIER)
    public DataSource storeDbDataSource(StoreDbProperties properties) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setPoolName("store-db");
        dataSource.setJdbcUrl(properties.url());
        dataSource.setUsername(properties.username());
        dataSource.setPassword(properties.password());
        dataSource.setMaximumPoolSize(properties.poolSize());
        dataSource.setMinimumIdle(1);
        // The pool starts even when store-db is down; the storage is then reported DOWN.
        dataSource.setInitializationFailTimeout(-1);
        return dataSource;
    }

    @Bean(defaultCandidate = false)
    @Qualifier(QUALIFIER)
    public JdbcTemplate storeDbJdbcTemplate(@Qualifier(QUALIFIER) DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    /** Applies the store-db schema (db/migration/store) at startup. */
    @Bean(initMethod = "migrate", defaultCandidate = false)
    @Qualifier(QUALIFIER)
    public Flyway storeDbFlyway(@Qualifier(QUALIFIER) DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/store")
                .load();
    }

    @ConfigurationProperties("filegrid.store-db")
    public record StoreDbProperties(String url, String username, String password, int poolSize) {
    }
}
