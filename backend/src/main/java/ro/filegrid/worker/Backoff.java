package ro.filegrid.worker;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * Exponential backoff with jitter, from the {@code retry} section of the
 * policy: the n-th wait is {@code initial * 2^(n-1)}, capped at {@code max},
 * then spread uniformly over [50%, 100%] of that value so that workers which
 * failed together do not all retry at the same instant.
 */
public final class Backoff {

    private final Duration initial;
    private final Duration max;
    private final DoubleSupplier random;

    public Backoff(Duration initial, Duration max) {
        this(initial, max, () -> ThreadLocalRandom.current().nextDouble());
    }

    Backoff(Duration initial, Duration max, DoubleSupplier random) {
        this.initial = initial;
        this.max = max;
        this.random = random;
    }

    /**
     * @param failedAttempt number of the attempt that just failed (1 for the first)
     */
    public Duration delayAfter(int failedAttempt) {
        long cap = max.toMillis();
        long base = initial.toMillis();
        int shift = Math.min(Math.max(failedAttempt - 1, 0), 30);
        long exponential = base > (cap >> shift) ? cap : Math.min(cap, base << shift);
        double factor = 0.5 + random.getAsDouble() * 0.5;
        return Duration.ofMillis(Math.round(exponential * factor));
    }
}
