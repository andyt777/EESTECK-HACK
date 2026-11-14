"""Settings from environment variables, overridable on the command line.

Volume model: every meter sends one reading per *tick*; a reading covers
``INTERVAL_MINUTES`` of simulated time (one sample per minute), and the
simulated clock advances by that much at every tick. Real time between two
ticks is ``INTERVAL_SECONDS / (RATE_MULTIPLIER * TIME_SCALE)``, so

    readings per real minute = METERS * 60 / INTERVAL_SECONDS * RATE_MULTIPLIER * TIME_SCALE

MODE=peak sets RATE_MULTIPLIER to 10 unless it is given explicitly; MODE=storm
keeps the normal rate and adds bursts at x10.
"""

from __future__ import annotations

import argparse
import os
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from pathlib import Path

MODES = ("normal", "peak", "storm")
DEFAULT_TOKEN_URL = "http://keycloak:8080/auth/realms/filegrid/protocol/openid-connect/token"


@dataclass(frozen=True)
class Settings:
    api_url: str = "http://localhost:8080/api"
    mode: str = "normal"
    meters: int = 2000
    interval_seconds: float = 60.0
    interval_minutes: int = 15
    rate_multiplier: float = 1.0
    time_scale: float = 1.0
    duration_seconds: float | None = None
    batch_size: int = 250
    concurrency: int = 4
    duplicate_rate: float = 0.01
    prosumer_share: float = 0.10
    seed: int = 42
    sim_start: datetime = field(default_factory=lambda: floor_to_interval(datetime.now(UTC), 15))
    storm_burst_every_seconds: float = 120.0
    storm_burst_seconds: float = 30.0
    metrics_port: int = 9105
    topology_path: Path = Path("topology.yaml")
    anomalies_path: Path = Path("anomalies.yaml")
    output_dir: Path = Path("output")
    request_timeout_seconds: float = 30.0
    max_retry_backoff_seconds: float = 30.0
    # Keycloak client_credentials (Stage 11). Empty secret = no Authorization header (local runs).
    token_url: str = DEFAULT_TOKEN_URL
    client_id: str = "filegrid-simulator"
    client_secret: str = field(default="", repr=False)

    @property
    def tick_seconds(self) -> float:
        """Real seconds between two ticks at the base rate (bursts not included)."""
        return self.interval_seconds / (self.rate_multiplier * self.time_scale)

    @property
    def readings_per_minute(self) -> float:
        return self.meters * 60.0 / self.tick_seconds

    def period(self, tick: int) -> tuple[datetime, datetime]:
        start = self.sim_start + timedelta(minutes=self.interval_minutes * tick)
        return start, start + timedelta(minutes=self.interval_minutes)


def floor_to_interval(moment: datetime, minutes: int) -> datetime:
    moment = moment.astimezone(UTC).replace(second=0, microsecond=0)
    return moment - timedelta(minutes=moment.minute % minutes)


def _env(name: str, default: str | None = None) -> str | None:
    value = os.environ.get(name)
    return value if value not in (None, "") else default


def load(argv: list[str] | None = None, env: dict[str, str] | None = None) -> Settings:
    if env is not None:
        saved = dict(os.environ)
        os.environ.clear()
        os.environ.update(env)
    try:
        return _load(argv)
    finally:
        if env is not None:
            os.environ.clear()
            os.environ.update(saved)


def _load(argv: list[str] | None) -> Settings:
    parser = argparse.ArgumentParser(
        prog="filegrid-simulator", description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--api-url", default=_env("API_URL", Settings.api_url))
    parser.add_argument("--mode", choices=MODES, default=_env("MODE", _env("SIM_MODE", "normal")))
    parser.add_argument("--meters", type=int, default=int(_env("METERS", "2000")))
    parser.add_argument("--interval-seconds", type=float, default=float(_env("INTERVAL_SECONDS", "60")))
    parser.add_argument("--interval-minutes", type=int, default=int(_env("INTERVAL_MINUTES", "15")))
    parser.add_argument("--rate-multiplier", type=float, default=_float_or_none(_env("RATE_MULTIPLIER")))
    parser.add_argument("--time-scale", type=float, default=float(_env("TIME_SCALE", "1")))
    parser.add_argument(
        "--duration",
        type=float,
        default=_float_or_none(_env("DURATION")),
        help="seconds to run (default: forever)",
    )
    parser.add_argument("--batch-size", type=int, default=int(_env("BATCH_SIZE", "250")))
    parser.add_argument("--concurrency", type=int, default=int(_env("CONCURRENCY", "4")))
    parser.add_argument("--duplicate-rate", type=float, default=float(_env("DUPLICATE_RATE", "0.01")))
    parser.add_argument("--seed", type=int, default=int(_env("SIM_SEED", "42")))
    parser.add_argument("--sim-start", default=_env("SIM_START"))
    parser.add_argument("--metrics-port", type=int, default=int(_env("METRICS_PORT", "9105")))
    parser.add_argument("--topology", default=_env("TOPOLOGY", "topology.yaml"))
    parser.add_argument("--anomalies", default=_env("ANOMALIES", "anomalies.yaml"))
    parser.add_argument("--output-dir", default=_env("OUTPUT_DIR", "output"))
    args = parser.parse_args(argv)

    rate = args.rate_multiplier
    if rate is None:
        rate = 10.0 if args.mode == "peak" else 1.0
    interval_minutes = args.interval_minutes
    sim_start = (
        datetime.fromisoformat(args.sim_start.replace("Z", "+00:00")).astimezone(UTC)
        if args.sim_start
        else floor_to_interval(datetime.now(UTC), interval_minutes)
    )
    settings = Settings(
        api_url=args.api_url.rstrip("/"),
        mode=args.mode,
        meters=args.meters,
        interval_seconds=args.interval_seconds,
        interval_minutes=interval_minutes,
        rate_multiplier=rate,
        time_scale=args.time_scale,
        duration_seconds=args.duration,
        batch_size=args.batch_size,
        concurrency=args.concurrency,
        duplicate_rate=args.duplicate_rate,
        seed=args.seed,
        sim_start=sim_start,
        metrics_port=args.metrics_port,
        topology_path=Path(args.topology),
        anomalies_path=Path(args.anomalies),
        output_dir=Path(args.output_dir),
        # Secrets only from the environment, never from the command line (visible in `ps`).
        token_url=_env("KC_TOKEN_URL", DEFAULT_TOKEN_URL),
        client_id=_env("KC_SIMULATOR_CLIENT_ID", "filegrid-simulator"),
        client_secret=_env("KC_SIMULATOR_SECRET", ""),
    )
    validate(settings)
    return settings


def validate(settings: Settings) -> None:
    problems = []
    if settings.meters < 1:
        problems.append("METERS must be at least 1")
    if not 1 <= settings.batch_size <= 500:
        problems.append(
            "BATCH_SIZE must be between 1 and 500 (the api accepts at most 500 readings per batch)"
        )
    if settings.interval_seconds <= 0 or settings.rate_multiplier <= 0 or settings.time_scale <= 0:
        problems.append("INTERVAL_SECONDS, RATE_MULTIPLIER and TIME_SCALE must be positive")
    if not 1 <= settings.interval_minutes <= 60:
        problems.append("INTERVAL_MINUTES must be between 1 and 60")
    if not 0 <= settings.duplicate_rate <= 0.5:
        problems.append("DUPLICATE_RATE must be between 0 and 0.5")
    if problems:
        raise SystemExit("Invalid simulator settings: " + "; ".join(problems))


def _float_or_none(value: str | None) -> float | None:
    return float(value) if value is not None else None
