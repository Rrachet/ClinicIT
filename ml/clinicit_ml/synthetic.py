"""A small, fast, deterministic synthetic dataset for unit tests.

Rows look like the exported dataset (same columns and value ranges) and follow a known
story: each patient ahead costs roughly the doctor's consultation length, the patient in the
room costs whatever is left of theirs, and doctors are a little quicker when the room is
full. That last effect is something the baseline cannot see and a model can learn, so tests
can check that training picks it up. The realistic dataset used for the documented results
is data/synthetic_wait_times.csv.gz, exported from a simulated clinic by the Spring Boot app.
"""

from __future__ import annotations

import datetime as dt
import random

from .dataset import Row


def generate(days: int = 40, rows_per_day: int = 60, seed: int = 7) -> list[Row]:
    rng = random.Random(seed)
    doctors = [7.0, 10.0, 14.0]
    first = dt.date(2026, 1, 5)
    rows: list[Row] = []
    for d in range(days):
        date = first + dt.timedelta(days=d)
        for i in range(rows_per_day):
            mean = doctors[i % len(doctors)]
            queue_length = rng.randint(1, 9)
            ahead = rng.randint(0, queue_length - 1)
            busy = 1 if rng.random() < 0.85 else 0
            active = rng.uniform(0, mean * 1.5) if busy else 0.0
            recent = mean * rng.uniform(0.8, 1.2) if rng.random() < 0.8 else None
            history = mean * rng.uniform(0.9, 1.1) if d > 3 else None
            rush = 1 - 0.04 * min(queue_length - 1, 5)
            wait = ahead * mean * rush + busy * max(mean - active, 0.0) + rng.gauss(0, 2.0)
            minute = 540 + rng.uniform(0, 240)
            rows.append({
                "local_date": date.isoformat(),
                "prediction_time": f"{date.isoformat()}T{int(minute // 60):02d}:{int(minute % 60):02d}:00Z",
                "snapshot": "JOIN",
                "day_of_week": date.isoweekday(),
                "minute_of_day": round(minute, 3),
                "patients_ahead": ahead,
                "queue_length": queue_length,
                "completed_today": rng.randint(0, 20),
                "doctor_busy": busy,
                "active_patient_minutes": round(active, 3),
                "calls_last_hour": rng.randint(0, 8),
                "recent_consultation_minutes": None if recent is None else round(recent, 3),
                "historical_consultation_minutes": None if history is None else round(history, 3),
                "historical_consultation_count": 0 if history is None else rng.randint(20, 400),
                "historical_wait_minutes": None if history is None else round(mean * 2.5, 3),
                "actual_wait_minutes": round(max(wait, 0.0), 3),
            })
    return rows


def write_csv(rows: list[Row], path) -> None:
    import csv

    from .features import FEATURE_NAMES, TARGET

    columns = ["local_date", "prediction_time", "snapshot", *FEATURE_NAMES, TARGET]
    with open(path, "w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(columns)
        for row in rows:
            writer.writerow(["" if row[c] is None else row[c] for c in columns])
