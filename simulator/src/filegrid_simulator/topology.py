"""Grid topology (topology.yaml) and the meters attached to it."""

from __future__ import annotations

import random
from dataclasses import dataclass
from pathlib import Path

import yaml


@dataclass(frozen=True)
class Transformer:
    id: str
    station: str
    region: str
    zone: str
    capacity_kva: int


@dataclass(frozen=True)
class Meter:
    id: str
    transformer: str
    station: str
    region: str
    region_name: str
    zone: str
    prosumer: bool
    base_kw: float  # average household demand
    pv_kwp: float  # installed PV (0 for consumers)


@dataclass(frozen=True)
class Topology:
    raw: dict
    region_names: dict[str, str]
    transformers: list[Transformer]

    @property
    def stations(self) -> list[dict]:
        return self.raw["stations"]


def load_topology(path: Path) -> Topology:
    with open(path, encoding="utf-8") as handle:
        raw = yaml.safe_load(handle)
    transformers = [
        Transformer(t["id"], t["station"], t["region"], t["zone"], int(t["capacityKva"]))
        for t in raw["transformers"]
    ]
    if not transformers:
        raise ValueError(f"{path} defines no transformer posts")
    names = {r["id"]: r["name"] for r in raw["regions"]}
    return Topology(raw, names, transformers)


def build_meters(topology: Topology, count: int, prosumer_share: float, seed: int) -> list[Meter]:
    """Deterministic for a given seed: the same meter id always sits on the same post."""
    rng = random.Random(seed)
    meters = []
    posts = topology.transformers
    for n in range(count):
        post = posts[n % len(posts)]
        prosumer = rng.random() < prosumer_share
        meters.append(
            Meter(
                id=f"MTR-{n + 1:06d}",
                transformer=post.id,
                station=post.station,
                region=post.region,
                region_name=topology.region_names.get(post.region, post.region),
                zone=post.zone,
                prosumer=prosumer,
                base_kw=round(rng.uniform(0.25, 0.9), 3),
                pv_kwp=round(rng.uniform(3.0, 10.0), 1) if prosumer else 0.0,
            )
        )
    return meters
