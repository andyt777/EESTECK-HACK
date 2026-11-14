from datetime import UTC, datetime
from pathlib import Path

import pytest

from filegrid_simulator.config import Settings

ROOT = Path(__file__).resolve().parent.parent


@pytest.fixture
def settings(tmp_path) -> Settings:
    return Settings(
        api_url="http://filegrid.test/api",
        meters=120,
        interval_seconds=0.01,
        batch_size=50,
        concurrency=2,
        seed=7,
        sim_start=datetime(2026, 11, 10, 0, 0, tzinfo=UTC),
        topology_path=ROOT / "topology.yaml",
        anomalies_path=ROOT / "anomalies.yaml",
        output_dir=tmp_path / "output",
        duplicate_rate=0.0,
    )
