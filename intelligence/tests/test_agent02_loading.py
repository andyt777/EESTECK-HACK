"""Agent 02 loading must survive readings whose stored bytes are not valid JSON/UTF-8 (for example the
demo `scripts/corrupt-file.sh` flips the first byte of one reading): such rows are skipped and reported,
the rest of the window is still analysed."""

from datetime import UTC, datetime, timedelta
from types import SimpleNamespace

import psycopg
import pytest

from filegrid_intelligence import agent02
from filegrid_intelligence.agent02 import FEATURES_SQL, READING_ID_AT_SQL, GridIntelligence

START = datetime(2026, 11, 14, 12, 0, tzinfo=UTC)


def reading(i: int) -> dict:
    start = START + timedelta(minutes=15 * i)
    return {
        "reading_id": f"r{i:03d}", "meter_id": f"MTR-{i % 3:03d}", "seq": i + 1, "period_start": start,
        "period_end": start + timedelta(minutes=15), "region": "Muntenia", "zone": "București",
        "station": "ST-MUN-01", "transformer": "PT-A", "created_at": start + timedelta(seconds=5),
        "prosumer": False, "n": 15, "kwh_import": 0.2, "kwh_export": 0.0, "min_import": 0.01,
        "v_min": 225.0, "v_avg": 229.0, "v_max": 233.0, "n_distinct": 15,
    }


class FakeStore:
    """Mimics store-db: the features query fails as a whole if any decoded row is corrupt."""

    def __init__(self, rows: list[dict], corrupt: set[str]):
        self.rows = sorted(rows, key=lambda r: (r["created_at"], r["reading_id"]))
        self.corrupt = corrupt
        self.feature_queries = 0

    def connection(self, timeout=None):
        return FakeConnection(self)

    def _candidates(self, params: dict) -> list[dict]:
        cursor = (params["after_ts"], params["after_id"])
        return [r for r in self.rows
                if (r["created_at"], r["reading_id"]) > cursor
                and r["period_start"] >= params["min_period"]
                and r["reading_id"] not in params["excluded"]]


class FakeConnection:
    def __init__(self, store: FakeStore):
        self.store = store

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

    def execute(self, sql, params=None):
        if sql is FEATURES_SQL:
            self.store.feature_queries += 1
            page = self.store._candidates(params)[: params["limit"]]
            if any(r["reading_id"] in self.store.corrupt for r in page):
                raise psycopg.errors.CharacterNotInRepertoire('invalid byte sequence for encoding "UTF8": 0x84')
            return SimpleNamespace(fetchall=lambda: page)
        if sql is READING_ID_AT_SQL:
            candidates = self.store._candidates(params)
            row = candidates[params["offset"]] if params["offset"] < len(candidates) else None
            return SimpleNamespace(fetchone=lambda: {"reading_id": row["reading_id"]} if row else None)
        if "max(period_start)" in sql:
            return SimpleNamespace(fetchone=lambda: {"m": max(r["period_start"] for r in self.store.rows)})
        raise AssertionError(f"unexpected SQL: {sql}")


def make_agent(store: FakeStore) -> GridIntelligence:
    return GridIntelligence(SimpleNamespace(window_hours=24.0), SimpleNamespace(store=store), None, None)


@pytest.fixture(autouse=True)
def small_pages(monkeypatch):
    monkeypatch.setattr(agent02, "PAGE", 4)


def test_clean_store_loads_every_reading():
    store = FakeStore([reading(i) for i in range(10)], corrupt=set())
    agent = make_agent(store)

    assert agent._load_new() == 10
    assert agent.excluded == set()


@pytest.mark.parametrize("corrupt", [{"r000"}, {"r003"}, {"r004"}, {"r009"}, {"r002", "r005", "r006"}])
def test_corrupt_readings_are_skipped_and_reported(corrupt):
    store = FakeStore([reading(i) for i in range(10)], corrupt=corrupt)
    agent = make_agent(store)

    loaded = agent._load_new()

    assert loaded == 10 - len(corrupt)
    assert agent.excluded == corrupt
    assert set(agent.df["reading_id"]) == {f"r{i:03d}" for i in range(10)} - corrupt


def test_an_error_not_caused_by_single_rows_is_raised_instead_of_excluding_everything(monkeypatch):
    monkeypatch.setattr(agent02, "MAX_SKIPPED_PER_PAGE", 3)
    store = FakeStore([reading(i) for i in range(10)], corrupt={f"r{i:03d}" for i in range(10)})
    agent = make_agent(store)

    with pytest.raises(psycopg.errors.DataError):
        agent._load_new()
    assert len(agent.excluded) == 3


def test_excluded_readings_are_not_searched_again_on_the_next_run():
    store = FakeStore([reading(i) for i in range(6)], corrupt={"r001"})
    agent = make_agent(store)
    agent._load_new()

    store.rows.extend(reading(i) for i in range(6, 8))
    store.rows.sort(key=lambda r: (r["created_at"], r["reading_id"]))
    store.feature_queries = 0
    assert agent._load_new() == 2
    assert store.feature_queries == 1  # one clean page, no bisection
