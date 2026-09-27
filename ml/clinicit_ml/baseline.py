"""The deterministic baseline, identical to BaselineWaitTime in the Spring Boot app (both are
tested against ml/contracts/baseline_cases.json):

    patients ahead × average consultation length

where the average is today's recent consultations if any, else the doctor's 28-day history,
else 10 minutes. Its range is a fixed, deliberately wide rule, not a statistical interval.
"""

from __future__ import annotations

import math
from typing import Mapping

VERSION = "baseline-v1"
DEFAULT_CONSULTATION_MINUTES = 10.0


def average_consultation_minutes(row: Mapping[str, float | None]) -> float:
    if row.get("recent_consultation_minutes") is not None:
        return float(row["recent_consultation_minutes"])
    if row.get("historical_consultation_minutes") is not None:
        return float(row["historical_consultation_minutes"])
    return DEFAULT_CONSULTATION_MINUTES


def minutes(row: Mapping[str, float | None]) -> float:
    return float(row["patients_ahead"]) * average_consultation_minutes(row)


def round_half_up(value: float) -> int:
    """Java's Math.round for non-negative values (Python's round() rounds halves to even)."""
    return math.floor(value + 0.5)


def estimate(row: Mapping[str, float | None]) -> tuple[int, int, int]:
    """(estimate, lower, upper) in whole minutes, exactly as the Java implementation."""
    m = minutes(row)
    point = round_half_up(m)
    lower = math.floor(m * 0.5)
    upper = math.ceil(m * 1.5 + 5)
    return point, min(lower, point), max(upper, point)
