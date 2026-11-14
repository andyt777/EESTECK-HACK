package ro.filegrid.meter;

import java.time.Instant;
import ro.filegrid.file.DataOrigin;

/**
 * One meter reading on its way from the api to worker-meter (queue
 * {@code filegrid.jobs.meter}, message type {@link #TYPE}). The reading itself
 * travels as the exact bytes that were hashed, so the worker can check that
 * nothing changed in flight and store precisely what was accepted.
 *
 * @param readingId SHA-256(meterId|periodStart), the idempotency key of the reading
 * @param sha256    SHA-256 of {@code content}
 * @param content   the reading as a small JSON file (serialised as base64 in the message)
 * @param batchKey  Idempotency-Key of the batch it arrived in
 * @param actor     who sent the batch (from CurrentActorProvider)
 */
public record MeterReadingMessage(String readingId, String meterId, Instant periodStart, Instant periodEnd, long seq,
                                  String region, String zone, String station, String transformer,
                                  DataOrigin dataOrigin, String sha256, byte[] content, String batchKey,
                                  String actor, Instant receivedAt) {

    /** AMQP message type that tells worker-meter this is a reading, not a transfer job. */
    public static final String TYPE = "meter-reading";
}
