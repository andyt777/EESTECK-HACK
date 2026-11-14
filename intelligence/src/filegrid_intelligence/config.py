"""Settings, all from environment variables (no secrets in code)."""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path


def _env(name: str, default: str = "") -> str:
    value = os.environ.get(name)
    return value if value not in (None, "") else default


@dataclass(frozen=True)
class Settings:
    meta_db_dsn: str = ""
    store_db_dsn: str = ""
    api_url: str = "http://api:8080/api"
    topology_path: Path = Path("/app/topology.yaml")
    ground_truth_path: Path = Path("/simulator-output/injected_anomalies.json")
    docs_dirs: tuple[Path, ...] = field(default_factory=lambda: (Path("/app/docs"), Path("/app/config")))
    analysis_interval_seconds: int = 60
    window_hours: float = 24.0
    anthropic_api_key: str = ""
    assistant_model: str = "claude-sonnet-5-5"
    llm_provider: str = ""
    llm_base_url: str = ""
    llm_api_key: str = ""
    llm_model: str = ""
    voice_provider: str = "simulated"
    twilio_account_sid: str = ""
    twilio_auth_token: str = ""
    twilio_from_number: str = ""
    incident_min_confidence: float = 0.8
    port: int = 8000

    @staticmethod
    def from_env() -> Settings:
        def dsn(prefix: str, host: str, db: str) -> str:
            user = _env(f"{prefix}_USER")
            password = _env(f"{prefix}_PASSWORD")
            return _env(f"{prefix}_DSN", f"postgresql://{user}:{password}@{host}:5432/{db}")

        return Settings(
            meta_db_dsn=dsn("META_DB", _env("META_DB_HOST", "meta-db"), "filegrid_meta"),
            store_db_dsn=dsn("STORE_DB", _env("STORE_DB_HOST", "store-db"), "filegrid_store"),
            api_url=_env("FILEGRID_API_URL", "http://api:8080/api").rstrip("/"),
            topology_path=Path(_env("TOPOLOGY_PATH", "/app/topology.yaml")),
            ground_truth_path=Path(_env("GROUND_TRUTH_PATH", "/simulator-output/injected_anomalies.json")),
            docs_dirs=tuple(Path(p) for p in _env("DOCS_DIRS", "/app/docs:/app/config").split(":") if p),
            analysis_interval_seconds=int(_env("INTELLIGENCE_ANALYSIS_INTERVAL_SECONDS", "60")),
            window_hours=float(_env("INTELLIGENCE_WINDOW_HOURS", "24")),
            anthropic_api_key=_env("ANTHROPIC_API_KEY"),
            assistant_model=_env("ASSISTANT_MODEL", "claude-sonnet-5-5"),
            llm_provider=_env("LLM_PROVIDER"),
            llm_base_url=_env("LLM_BASE_URL"),
            llm_api_key=_env("LLM_API_KEY"),
            llm_model=_env("LLM_MODEL", "gemini-3.5-flash"),
            voice_provider=_env("VOICE_PROVIDER", "simulated").lower(),
            twilio_account_sid=_env("TWILIO_ACCOUNT_SID"),
            twilio_auth_token=_env("TWILIO_AUTH_TOKEN"),
            twilio_from_number=_env("TWILIO_FROM_NUMBER"),
            incident_min_confidence=float(_env("INTELLIGENCE_INCIDENT_MIN_CONFIDENCE", "0.8")),
            port=int(_env("PORT", "8000")),
        )
