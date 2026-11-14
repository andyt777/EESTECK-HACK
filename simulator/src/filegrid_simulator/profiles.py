"""Realistic load and generation profiles (simple, deterministic models).

- daily curve: night trough, morning peak (~07-09), evening peak (~18-22);
- weekends: later, flatter morning and higher daytime use;
- temperature: synthetic daily/seasonal temperature; heating raises demand below 15 C;
- solar: clear-sky bell between sunrise and sunset (shorter in winter), scaled by PV size.
"""

from __future__ import annotations

import math
from datetime import datetime


def daily_factor(moment: datetime) -> float:
    hour = moment.hour + moment.minute / 60.0
    weekend = moment.weekday() >= 5
    morning_center = 9.0 if weekend else 7.5
    morning = (0.55 if weekend else 0.7) * math.exp(-((hour - morning_center) ** 2) / (2 * 1.2**2))
    evening = 1.0 * math.exp(-((hour - 19.5) ** 2) / (2 * 1.8**2))
    daytime = (0.35 if weekend else 0.2) * math.exp(-((hour - 13.5) ** 2) / (2 * 3.0**2))
    night = 0.35
    return night + morning + evening + daytime


def temperature_c(moment: datetime) -> float:
    """Seasonal mean (coldest mid-January) plus a daily swing (warmest ~15:00)."""
    day_of_year = moment.timetuple().tm_yday
    seasonal = 11.0 - 13.0 * math.cos(2 * math.pi * (day_of_year - 15) / 365.0)
    hour = moment.hour + moment.minute / 60.0
    daily = 4.0 * math.cos(2 * math.pi * (hour - 15.0) / 24.0)
    return seasonal + daily


def temperature_factor(moment: datetime) -> float:
    return (
        1.0 + 0.025 * max(0.0, 15.0 - temperature_c(moment)) + 0.015 * max(0.0, temperature_c(moment) - 26.0)
    )


def solar_fraction(moment: datetime) -> float:
    """Share of the installed PV power produced (0..1), clear sky."""
    day_of_year = moment.timetuple().tm_yday
    day_length = 12.0 + 3.5 * math.sin(2 * math.pi * (day_of_year - 80) / 365.0)  # hours
    sunrise, sunset = 13.0 - day_length / 2, 13.0 + day_length / 2
    hour = moment.hour + moment.minute / 60.0
    if hour <= sunrise or hour >= sunset:
        return 0.0
    elevation = math.sin(math.pi * (hour - sunrise) / (sunset - sunrise))
    peak = 0.55 + 0.3 * math.sin(2 * math.pi * (day_of_year - 80) / 365.0)
    return max(0.0, elevation * peak)


def demand_kw(base_kw: float, moment: datetime) -> float:
    return base_kw * daily_factor(moment) * temperature_factor(moment)
