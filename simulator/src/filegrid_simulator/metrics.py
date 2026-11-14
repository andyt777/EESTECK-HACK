"""Prometheus metrics of the simulator (scraped by Prometheus at simulator:9105)."""

from prometheus_client import Counter, Gauge, Histogram

SENT = Counter(
    "filegrid_simulator_readings_sent", "Readings sent to FileGrid (each attempt of a batch counts once)"
)
ACKED = Counter(
    "filegrid_simulator_readings_acked", "Readings acknowledged by FileGrid (202: accepted or duplicate)"
)
ACCEPTED = Counter("filegrid_simulator_readings_accepted", "Readings FileGrid reported as accepted (new)")
DUPLICATES_REPORTED = Counter(
    "filegrid_simulator_duplicates_reported", "Readings FileGrid reported as duplicates (already stored)"
)
DUPLICATES_SENT = Counter(
    "filegrid_simulator_duplicates_sent", "Readings deliberately sent again to demonstrate deduplication"
)
RETRIED = Counter(
    "filegrid_simulator_batches_retried",
    "Batch attempts repeated with the same Idempotency-Key after an error or timeout",
)
REJECTED = Counter("filegrid_simulator_batches_rejected", "Batches refused by FileGrid (4xx), not retried")
MISSING = Counter(
    "filegrid_simulator_readings_withheld", "Readings deliberately not sent (MISSING_READINGS anomaly)"
)
BATCH_SECONDS = Histogram(
    "filegrid_simulator_batch_seconds",
    "Time until a batch is acknowledged (with retries)",
    buckets=(0.05, 0.1, 0.25, 0.5, 1, 2, 5, 10, 30, 60),
)
RATE_MULTIPLIER = Gauge("filegrid_simulator_rate_multiplier", "Current rate multiplier (bursts included)")
TARGET_RPM = Gauge(
    "filegrid_simulator_target_readings_per_minute", "Configured readings per minute at base rate"
)
SIM_TIME = Gauge(
    "filegrid_simulator_simulated_time_seconds", "Simulated clock (start of the last period sent)"
)
TICK = Gauge("filegrid_simulator_tick", "Index of the last tick fully acknowledged")
