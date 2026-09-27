"""A trained wait-time model and the rules for when it may be used.

The model answers only when the situation looks like what it was trained on:
  * the doctor has at least `min_history` consultations in the previous 28 days, and
  * no more patients are ahead than in any training example.
Otherwise the row gets the deterministic baseline, labelled with the reason, instead of a
confident-looking number the model has no basis for.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Mapping, Sequence

import joblib
import numpy as np

from . import baseline
from .features import FEATURE_NAMES, to_matrix

ARTIFACT_FILE = "model.joblib"


@dataclass
class Prediction:
    source: str                  # "MODEL" or "BASELINE"
    estimated_wait_minutes: float
    lower_bound_minutes: float
    upper_bound_minutes: float
    reason: str | None = None


@dataclass
class WaitTimeModel:
    version: str
    algorithm: str
    point: Any
    lower: Any
    upper: Any
    interval_adjustment: float
    min_history: int
    max_patients_ahead: int
    feature_names: list[str] = field(default_factory=lambda: list(FEATURE_NAMES))
    metadata: dict[str, Any] = field(default_factory=dict)

    def save(self, directory: str | Path) -> Path:
        directory = Path(directory)
        directory.mkdir(parents=True, exist_ok=True)
        path = directory / ARTIFACT_FILE
        joblib.dump(self, path)
        return path

    @staticmethod
    def load(directory: str | Path) -> "WaitTimeModel":
        # joblib uses pickle: load only artifacts produced by our own training pipeline.
        model = joblib.load(Path(directory) / ARTIFACT_FILE)
        if not isinstance(model, WaitTimeModel) or model.feature_names != FEATURE_NAMES:
            raise ValueError("model artifact does not match this service's features")
        return model

    def applicability(self, row: Mapping[str, Any]) -> str | None:
        """None if the model may be used for this row, else the reason it may not."""
        if (row.get("historical_consultation_count") or 0) < self.min_history:
            return "INSUFFICIENT_HISTORY"
        if row["patients_ahead"] > self.max_patients_ahead:
            return "OUT_OF_TRAINING_RANGE"
        return None

    def predict(self, rows: Sequence[Mapping[str, Any]]) -> list[Prediction]:
        results: list[Prediction | None] = [None] * len(rows)
        usable = []
        for i, row in enumerate(rows):
            reason = self.applicability(row)
            if reason is None:
                usable.append(i)
            else:
                point, lower, upper = baseline.estimate(row)
                results[i] = Prediction("BASELINE", point, lower, upper, reason)
        if usable:
            x = to_matrix([rows[i] for i in usable])
            point, lower, upper = self.raw_intervals(x)
            for k, i in enumerate(usable):
                results[i] = Prediction("MODEL", point[k], lower[k], upper[k])
        return results  # type: ignore[return-value]

    def raw_intervals(self, x: np.ndarray) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
        """Point estimate and calibrated 80% interval, clipped so 0 <= lower <= point <= upper."""
        point = np.maximum(self.point.predict(x), 0.0)
        lower = np.minimum(self.lower.predict(x) - self.interval_adjustment, point)
        upper = np.maximum(self.upper.predict(x) + self.interval_adjustment, point)
        lower = np.maximum(lower, 0.0)
        assert not np.any(~np.isfinite(point)), "non-finite prediction"
        return point, lower, upper
