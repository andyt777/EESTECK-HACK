package ro.filegrid.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class BackoffTest {

    @Test
    void doublesFromTheInitialDelayUpToTheCap() {
        Backoff noJitter = new Backoff(Duration.ofSeconds(1), Duration.ofSeconds(10), () -> 1.0);
        assertThat(noJitter.delayAfter(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(noJitter.delayAfter(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(noJitter.delayAfter(3)).isEqualTo(Duration.ofSeconds(4));
        assertThat(noJitter.delayAfter(4)).isEqualTo(Duration.ofSeconds(8));
        assertThat(noJitter.delayAfter(5)).isEqualTo(Duration.ofSeconds(10));
        assertThat(noJitter.delayAfter(60)).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void jitterKeepsTheDelayBetweenHalfAndAllOfTheExponentialValue() {
        Backoff lowest = new Backoff(Duration.ofSeconds(2), Duration.ofSeconds(60), () -> 0.0);
        assertThat(lowest.delayAfter(3)).isEqualTo(Duration.ofSeconds(4));
        Backoff random = new Backoff(Duration.ofSeconds(2), Duration.ofSeconds(60));
        for (int i = 0; i < 100; i++) {
            Duration delay = random.delayAfter(3);
            assertThat(delay).isBetween(Duration.ofSeconds(4), Duration.ofSeconds(8));
        }
    }
}
