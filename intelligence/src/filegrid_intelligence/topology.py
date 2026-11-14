"""Grid topology (regions, stations, transformer posts) from simulator/topology.yaml."""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

import yaml


@dataclass
class Topology:
    canvas: dict = field(default_factory=lambda: {"width": 1000, "height": 600})
    regions: list[dict] = field(default_factory=list)
    stations: list[dict] = field(default_factory=list)
    posts: list[dict] = field(default_factory=list)

    def region_id(self, value: str | None) -> str | None:
        """Accepts a region id ("MUN") or name ("Muntenia")."""
        if not value:
            return None
        for region in self.regions:
            if value in (region["id"], region.get("name")):
                return region["id"]
        return value

    def region_name(self, region_id: str | None) -> str | None:
        for region in self.regions:
            if region["id"] == region_id:
                return region.get("name", region_id)
        return region_id

    def post(self, post_id: str) -> dict | None:
        return next((p for p in self.posts if p["id"] == post_id), None)

    def station(self, station_id: str) -> dict | None:
        return next((s for s in self.stations if s["id"] == station_id), None)


def load_topology(path: Path) -> Topology:
    if not path.exists():
        return Topology()
    with open(path, encoding="utf-8") as handle:
        raw = yaml.safe_load(handle) or {}
    return Topology(
        canvas=raw.get("canvas", {"width": 1000, "height": 600}),
        regions=list(raw.get("regions", [])),
        stations=list(raw.get("stations", [])),
        posts=list(raw.get("transformers", [])),
    )
