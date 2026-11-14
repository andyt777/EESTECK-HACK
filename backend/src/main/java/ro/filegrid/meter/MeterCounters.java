package ro.filegrid.meter;

import java.util.HashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Running totals of meter ingestion kept in meta-db ({@code meter_counter}),
 * so that the totals of every api and worker instance add up in one place.
 */
@Component
public class MeterCounters {

    public static final String READINGS_STORED = "readings_stored";
    public static final String DUPLICATES_REJECTED = "duplicates_rejected";
    public static final String DUPLICATES_WRITTEN = "duplicates_written";

    private final JdbcTemplate metaDb;

    public MeterCounters(JdbcTemplate metaDb) {
        this.metaDb = metaDb;
    }

    public void add(String name, long delta) {
        if (delta == 0) {
            return;
        }
        metaDb.update("UPDATE meter_counter SET value = value + ?, updated_at = now() WHERE name = ?", delta, name);
    }

    public void set(String name, long value) {
        metaDb.update("UPDATE meter_counter SET value = ?, updated_at = now() WHERE name = ?", value, name);
    }

    public Map<String, Long> all() {
        Map<String, Long> values = new HashMap<>();
        metaDb.query("SELECT name, value FROM meter_counter", rs -> {
            values.put(rs.getString("name"), rs.getLong("value"));
        });
        return values;
    }
}
