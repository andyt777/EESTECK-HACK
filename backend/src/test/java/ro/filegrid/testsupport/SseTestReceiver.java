package ro.filegrid.testsupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.awaitility.Awaitility;

/**
 * Non-blocking Server-Sent Events (SSE) test receiver.
 * Connects asynchronously to /api/events/stream, parses incoming data: {...} JSON events,
 * buffers them in an awaiting queue, and provides predicate-based waiting via Awaitility.
 */
public class SseTestReceiver implements AutoCloseable {

    public static final String ROMANIAN_AGENT01_PATTERN =
            "^Am primit un fișier nou\\.$|"
            + "^Analizez conținutul…$|"
            + "^Am identificat categoria: .+\\.$|"
            + "^Am selectat (S3|FTP|Database) pentru acest fișier\\.$|"
            + "^Transfer în desfășurare…$|"
            + "^Transferul a fost întrerupt\\. Îl voi relua\\.$|"
            + "^Fișier stocat și verificat\\.$|"
            + "^Neconcordanță de integritate la verificare.*$|"
            + "^Storage-ul principal nu a putut fi folosit.*$|"
            + "^Nu există momentan o destinație disponibilă\\. Fișierul a rămas în așteptare\\.$|"
            + "^Transferul a eșuat.*$";

    private static final Set<String> VALID_CATEGORIES = Set.of(
            "METER_READING", "FAULT_RECORD", "CONNECTION_DOCUMENT", "DRONE_VIDEO"
    );

    private static final Set<String> VALID_STATUSES = Set.of(
            "RECEIVED", "ANALYZING", "CATEGORIZED", "DESTINATION_SELECTED",
            "TRANSFERRING", "TRANSFER_RESUMED", "STORED_VERIFIED",
            "MISMATCH", "FALLBACK", "PENDING", "TRANSFER_FAILED"
    );

    private static final Set<String> VALID_STORAGES = Set.of(
            "S3", "FTP", "DATABASE"
    );

    private final URI endpoint;
    private final BlockingQueue<JsonNode> eventQueue = new LinkedBlockingQueue<>();
    private final List<JsonNode> allReceivedEvents = new CopyOnWriteArrayList<>();
    private final List<String> malformedPayloads = new CopyOnWriteArrayList<>();
    private final List<Throwable> connectionErrors = new CopyOnWriteArrayList<>();
    private final CompletableFuture<Void> streamFuture;
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile boolean closed = false;

    public SseTestReceiver(URI sseEndpoint) {
        this.endpoint = sseEndpoint;
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(sseEndpoint)
                .header("Accept", "text/event-stream")
                .header("Cache-Control", "no-cache")
                .GET()
                .build();

        this.streamFuture = client.sendAsync(request, HttpResponse.BodyHandlers.ofLines())
                .thenAccept(response -> {
                    response.body().forEach(line -> {
                        if (closed) return;
                        if (line != null && line.startsWith("data:")) {
                            String dataPayload = line.substring(5).trim();
                            if (!dataPayload.isEmpty()) {
                                try {
                                    JsonNode event = mapper.readTree(dataPayload);
                                    eventQueue.add(event);
                                    allReceivedEvents.add(event);
                                } catch (IOException ex) {
                                    malformedPayloads.add("Payload: [" + dataPayload + "], error: " + ex.getMessage());
                                }
                            }
                        }
                    });
                })
                .exceptionally(ex -> {
                    if (!closed) {
                        connectionErrors.add(ex);
                    }
                    return null;
                });
    }

    public static SseTestReceiver connect(String baseUrl, UUID fileIdFilter, String categoryFilter) {
        StringBuilder urlBuilder = new StringBuilder(baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl);
        urlBuilder.append("/events/stream");
        List<String> params = new ArrayList<>();
        if (fileIdFilter != null) {
            params.add("fileId=" + fileIdFilter);
        }
        if (categoryFilter != null && !categoryFilter.isBlank()) {
            params.add("category=" + categoryFilter);
        }
        if (!params.isEmpty()) {
            urlBuilder.append("?").append(String.join("&", params));
        }
        return new SseTestReceiver(URI.create(urlBuilder.toString()));
    }

    public URI getEndpoint() {
        return endpoint;
    }

    public List<JsonNode> getAllReceivedEvents() {
        return Collections.unmodifiableList(allReceivedEvents);
    }

    public List<JsonNode> getEventsForFile(UUID fileId) {
        if (fileId == null) {
            return Collections.emptyList();
        }
        return allReceivedEvents.stream()
                .filter(e -> e.has("fileId") && fileId.toString().equalsIgnoreCase(e.get("fileId").asText()))
                .toList();
    }

    public List<String> getMalformedPayloads() {
        return Collections.unmodifiableList(malformedPayloads);
    }

    public List<Throwable> getConnectionErrors() {
        return Collections.unmodifiableList(connectionErrors);
    }

    public void assertNoErrors() {
        if (!malformedPayloads.isEmpty()) {
            throw new AssertionError("Malformed JSON SSE payloads encountered: " + malformedPayloads);
        }
        if (!connectionErrors.isEmpty()) {
            throw new AssertionError("SSE stream connection error encountered", connectionErrors.get(0));
        }
    }

    public static void assertValidEventSchema(JsonNode event) {
        Objects.requireNonNull(event, "SSE event must not be null");

        if (!event.hasNonNull("fileId")) {
            throw new AssertionError("SSE event missing mandatory 'fileId' field: " + event);
        }
        try {
            UUID.fromString(event.get("fileId").asText());
        } catch (IllegalArgumentException e) {
            throw new AssertionError("Invalid UUID format for 'fileId': " + event.get("fileId").asText());
        }

        if (!event.hasNonNull("category") || !VALID_CATEGORIES.contains(event.get("category").asText())) {
            throw new AssertionError("SSE event missing or invalid 'category' field: " + event.get("category"));
        }

        if (!event.hasNonNull("status") || !VALID_STATUSES.contains(event.get("status").asText())) {
            throw new AssertionError("SSE event missing or invalid 'status' field: " + event.get("status"));
        }

        if (event.hasNonNull("storage")) {
            String storage = event.get("storage").asText();
            if (!VALID_STORAGES.contains(storage)) {
                throw new AssertionError("SSE event contains invalid 'storage' field: " + storage);
            }
        }

        if (!event.hasNonNull("message")) {
            throw new AssertionError("SSE event missing mandatory 'message' field: " + event);
        }
        String msg = event.get("message").asText();
        if (!msg.matches(ROMANIAN_AGENT01_PATTERN)) {
            throw new AssertionError("SSE message does not match Agent 01 Romanian text contract: '" + msg + "'");
        }

        if (!event.has("bytesTransferred") || !event.get("bytesTransferred").isIntegralNumber()) {
            throw new AssertionError("SSE event missing or non-integral 'bytesTransferred': " + event.get("bytesTransferred"));
        }
        long bytesTransferred = event.get("bytesTransferred").asLong();
        if (bytesTransferred < 0) {
            throw new AssertionError("SSE bytesTransferred cannot be negative: " + bytesTransferred);
        }

        if (!event.has("totalBytes") || !event.get("totalBytes").isIntegralNumber()) {
            throw new AssertionError("SSE event missing or non-integral 'totalBytes': " + event.get("totalBytes"));
        }
        long totalBytes = event.get("totalBytes").asLong();
        if (totalBytes < 0) {
            throw new AssertionError("SSE totalBytes cannot be negative: " + totalBytes);
        }
        if (bytesTransferred > totalBytes && totalBytes > 0) {
            throw new AssertionError("SSE bytesTransferred (" + bytesTransferred
                    + ") exceeds totalBytes (" + totalBytes + ")");
        }

        if (!event.hasNonNull("timestamp")) {
            throw new AssertionError("SSE event missing mandatory 'timestamp' field: " + event);
        }
        try {
            Instant.parse(event.get("timestamp").asText());
        } catch (Exception e) {
            throw new AssertionError("Invalid ISO-8601 format for 'timestamp': " + event.get("timestamp").asText());
        }

        if (!event.hasNonNull("traceId") || event.get("traceId").asText().isBlank()) {
            throw new AssertionError("SSE event missing or blank mandatory 'traceId': " + event.get("traceId"));
        }
    }

    public int getEventCount() {
        return allReceivedEvents.size();
    }

    /**
     * Polls the next event from the queue, blocking up to the timeout.
     */
    public JsonNode pollEvent(Duration timeout) throws InterruptedException {
        return eventQueue.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Waits until an event matching the given predicate arrives.
     */
    public JsonNode awaitEvent(Predicate<JsonNode> matcher, Duration timeout) {
        return Awaitility.await()
                .atMost(timeout)
                .pollInterval(Duration.ofMillis(100))
                .until(() -> {
                    for (JsonNode event : allReceivedEvents) {
                        if (matcher.test(event)) {
                            return event;
                        }
                    }
                    return null;
                }, Objects::nonNull);
    }

    /**
     * Waits for an event with matching fileId and status.
     */
    public JsonNode awaitStatus(UUID fileId, String expectedStatus, Duration timeout) {
        JsonNode matched = awaitEvent(event -> {
            boolean fileMatches = fileId == null ||
                    (event.has("fileId") && fileId.toString().equalsIgnoreCase(event.get("fileId").asText()));
            boolean statusMatches = event.has("status") && expectedStatus.equalsIgnoreCase(event.get("status").asText());
            return fileMatches && statusMatches;
        }, timeout);
        assertNoErrors();
        if (matched != null) {
            assertValidEventSchema(matched);
        }
        return matched;
    }

    /**
     * Waits until at least expectedCount events have been received.
     */
    public void awaitEventCount(int expectedCount, Duration timeout) {
        Awaitility.await()
                .atMost(timeout)
                .pollInterval(Duration.ofMillis(100))
                .until(() -> allReceivedEvents.size() >= expectedCount);
    }

    @Override
    public void close() {
        closed = true;
        streamFuture.cancel(true);
    }
}
