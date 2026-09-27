"""Loading the exported dataset and splitting it by time.

The dataset is produced by the Spring Boot app (WaitTimeDatasetBuilder) from the immutable
operational history: one row per moment a waiting patient could have been shown an estimate,
with features computed only from what was known at that moment.

Split: whole clinic days, in date order. The oldest days train the model, the next ones
choose between candidate models and calibrate the intervals, and the most recent ones are
held out for the final evaluation. No day is shared between sets, and every training day
comes before every validation day, which comes before every test day, so nothing learned
during training can come from a later day. (A random row split would put moments from the
same morning in both training and test.)
"""

from __future__ import annotations

import csv
import gzip
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from .features import FEATURE_NAMES, OPTIONAL, TARGET

Row = dict[str, Any]

INTEGER_COLUMNS = {"day_of_week", "patients_ahead", "queue_length", "completed_today", "doctor_busy",
                   "calls_last_hour", "historical_consultation_count"}


def load_csv(path: str | Path) -> list[Row]:
    path = Path(path)
    opener = gzip.open if path.suffix == ".gz" else open
    with opener(path, "rt", encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle)
        missing = [c for c in [*FEATURE_NAMES, TARGET, "local_date"] if c not in (reader.fieldnames or [])]
        if missing:
            raise ValueError(f"dataset is missing columns: {missing}")
        return [_parse(raw) for raw in reader]


def _parse(raw: dict[str, str]) -> Row:
    row: Row = {"local_date": raw["local_date"], "prediction_time": raw.get("prediction_time"),
                "snapshot": raw.get("snapshot")}
    for name in FEATURE_NAMES:
        text = raw[name]
        if text == "":
            if name not in OPTIONAL:
                raise ValueError(f"required feature {name} is empty")
            row[name] = None
        else:
            row[name] = int(text) if name in INTEGER_COLUMNS else float(text)
    row[TARGET] = float(raw[TARGET])
    return row


@dataclass(frozen=True)
class Split:
    train: list[Row]
    validation: list[Row]
    test: list[Row]
    train_days: list[str]
    validation_days: list[str]
    test_days: list[str]

    def describe(self) -> dict[str, Any]:
        def part(rows: list[Row], days: list[str]) -> dict[str, Any]:
            return {"rows": len(rows), "days": len(days), "first_day": days[0], "last_day": days[-1]}
        return {"method": "temporal, by clinic-local day",
                "train": part(self.train, self.train_days),
                "validation": part(self.validation, self.validation_days),
                "test": part(self.test, self.test_days)}


def temporal_split(rows: list[Row], train: float = 0.70, validation: float = 0.15) -> Split:
    """Oldest `train` share of days → train, next `validation` share → validation, rest → test."""
    days = sorted({row["local_date"] for row in rows})
    if len(days) < 3:
        raise ValueError("need at least 3 distinct days for a temporal split")
    n_train = max(1, int(round(len(days) * train)))
    n_validation = max(1, int(round(len(days) * validation)))
    if n_train + n_validation >= len(days):
        n_train = len(days) - n_validation - 1
    train_days = days[:n_train]
    validation_days = days[n_train:n_train + n_validation]
    test_days = days[n_train + n_validation:]
    by_set = {d: "train" for d in train_days} | {d: "validation" for d in validation_days} | {d: "test" for d in test_days}
    parts: dict[str, list[Row]] = {"train": [], "validation": [], "test": []}
    for row in rows:
        parts[by_set[row["local_date"]]].append(row)
    return Split(parts["train"], parts["validation"], parts["test"], train_days, validation_days, test_days)
