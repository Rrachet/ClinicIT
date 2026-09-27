"""The model's inputs. Must match WaitTimeFeatures in the Spring Boot app, which builds them
"as of" the prediction moment from the operational history (docs/AI.md, "Features").

Only operational facts about the queue: no patient name, age, sex, reason for visit or any
other patient attribute. Identifiers (clinic, doctor, patient) are not features either.
"""

from __future__ import annotations

import math
from typing import Mapping, Sequence

import numpy as np

# (column in the dataset, JSON name sent by Spring Boot)
FEATURES: list[tuple[str, str]] = [
    ("day_of_week", "dayOfWeek"),
    ("minute_of_day", "minuteOfDay"),
    ("patients_ahead", "patientsAhead"),
    ("queue_length", "queueLength"),
    ("completed_today", "completedToday"),
    ("doctor_busy", "doctorBusy"),
    ("active_patient_minutes", "activePatientMinutes"),
    ("calls_last_hour", "callsLastHour"),
    ("recent_consultation_minutes", "recentConsultationMinutes"),
    ("historical_consultation_minutes", "historicalConsultationMinutes"),
    ("historical_consultation_count", "historicalConsultationCount"),
    ("historical_wait_minutes", "historicalWaitMinutes"),
]

FEATURE_NAMES: list[str] = [name for name, _ in FEATURES]

TARGET = "actual_wait_minutes"

# Dataset columns that describe the example but must never be model inputs: the target,
# and when/what the snapshot was (used only to split by time).
NOT_FEATURES = {"local_date", "prediction_time", "snapshot", TARGET}

# Columns that may be missing (no consultations yet today / no history): NaN for the model.
OPTIONAL = {"recent_consultation_minutes", "historical_consultation_minutes", "historical_wait_minutes"}


def to_matrix(rows: Sequence[Mapping[str, float | None]]) -> np.ndarray:
    """Feature matrix in FEATURE_NAMES order; missing optional values become NaN."""
    matrix = np.empty((len(rows), len(FEATURE_NAMES)), dtype=float)
    for i, row in enumerate(rows):
        for j, name in enumerate(FEATURE_NAMES):
            value = row.get(name)
            matrix[i, j] = math.nan if value is None else float(value)
    return matrix
