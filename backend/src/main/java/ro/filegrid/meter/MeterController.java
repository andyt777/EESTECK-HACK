package ro.filegrid.meter;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ro.filegrid.file.DataOrigin;
import ro.filegrid.security.AccessPolicy;
import ro.filegrid.security.CurrentActorProvider;
import ro.filegrid.storage.database.StoreDbConfig;
import ro.filegrid.text.Texts;

/**
 * Meter readings: {@code POST /api/meters/readings} (batches from the smart
 * meters) and {@code GET /api/meters/stats}.
 */
@RestController
@RequestMapping("/meters")
@Profile("api")
public class MeterController {

    static final String PERMISSION_WRITE = "meters:write";
    static final String PERMISSION_READ = "meters:read";

    private final MeterIngestionService ingestion;
    private final MeterReconciliation reconciliation;
    private final JdbcTemplate storeDb;
    private final CurrentActorProvider actors;
    private final AccessPolicy access;
    private final Texts texts;

    public MeterController(MeterIngestionService ingestion, MeterReconciliation reconciliation,
                           @Qualifier(StoreDbConfig.QUALIFIER) JdbcTemplate storeDb, CurrentActorProvider actors,
                           AccessPolicy access, Texts texts) {
        this.ingestion = ingestion;
        this.reconciliation = reconciliation;
        this.storeDb = storeDb;
        this.actors = actors;
        this.access = access;
        this.texts = texts;
    }

    /** 202 only after every accepted reading is confirmed by RabbitMQ. */
    @PostMapping("/readings")
    public ResponseEntity<MeterIngestionService.BatchResponse> readings(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody JsonNode body) {
        String actor = actors.currentActor();
        if (!access.isAllowed(actor, PERMISSION_WRITE)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        MeterIngestionService.Result result = ingestion.ingest(idempotencyKey, body, actor);
        return ResponseEntity.accepted()
                .header("Idempotent-Replayed", String.valueOf(result.replayed()))
                .body(result.response());
    }

    /**
     * Live numbers: readings per minute (store-db, right now) and the results
     * of the last reconciliation (gaps, duplicates).
     */
    @GetMapping("/stats")
    public MeterStats stats() {
        String actor = actors.currentActor();
        if (!access.isAllowed(actor, PERMISSION_READ)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        Map<String, Object> live = storeDb.queryForMap("""
                SELECT count(*) FILTER (WHERE created_at > now() - interval '1 minute')  AS last_minute,
                       count(*) FILTER (WHERE created_at > now() - interval '5 minutes') AS last_five,
                       max(created_at)                                                    AS last_at,
                       count(*) FILTER (WHERE created_at > now() - interval '5 minutes'
                                          AND data_origin = 'SIMULATED')                  AS simulated_five
                  FROM meter_reading_blob
                 WHERE created_at > now() - interval '5 minutes'
                """);
        long lastMinute = ((Number) live.get("last_minute")).longValue();
        long lastFive = ((Number) live.get("last_five")).longValue();
        long simulatedFive = ((Number) live.get("simulated_five")).longValue();
        Instant lastAt = live.get("last_at") instanceof Timestamp t ? t.toInstant() : null;
        MeterReconciliation.Snapshot snapshot = reconciliation.last();
        DataOrigin origin = lastFive > 0 && simulatedFive == lastFive ? DataOrigin.SIMULATED
                : lastFive > 0 ? DataOrigin.REAL : null;
        return new MeterStats(lastMinute, lastFive / 5.0, snapshot.readingsStored(), snapshot.meters(),
                snapshot.gaps(), snapshot.duplicatesRejected(), snapshot.duplicatesWritten(), lastAt,
                snapshot.at(), origin);
    }

    /**
     * @param readingsLastMinute      readings stored during the last 60 s
     * @param readingsPerMinute       average over the last 5 minutes
     * @param readingsStored          total stored and verified (from the last reconciliation)
     * @param dataOrigin              origin of the readings of the last 5 minutes (SIMULATED for the simulator)
     */
    public record MeterStats(long readingsLastMinute, double readingsPerMinute, long readingsStored, long activeMeters,
                             long gaps, long duplicatesRejected, long duplicatesWritten, Instant lastReadingAt,
                             Instant reconciledAt, DataOrigin dataOrigin) {
    }

    @ExceptionHandler(MeterIngestionService.InvalidBatchException.class)
    ResponseEntity<ProblemDetail> invalid(MeterIngestionService.InvalidBatchException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, texts.get("meter.error.title"));
        problem.setType(URI.create("urn:filegrid:meter:invalid-batch"));
        problem.setProperty("errors", e.errors());
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(MeterIngestionService.IdempotencyConflictException.class)
    ResponseEntity<ProblemDetail> conflict(MeterIngestionService.IdempotencyConflictException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        problem.setType(URI.create("urn:filegrid:meter:idempotency-key-reused"));
        return ResponseEntity.unprocessableEntity().body(problem);
    }

    @ExceptionHandler(MeterReadingPublisher.PublishException.class)
    ResponseEntity<ProblemDetail> unavailable(MeterReadingPublisher.PublishException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                texts.get("meter.error.queue"));
        problem.setType(URI.create("urn:filegrid:meter:queue-unavailable"));
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "2").body(problem);
    }
}
