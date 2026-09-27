"""Evaluation metrics, in minutes."""

from __future__ import annotations

from typing import Sequence

import numpy as np


def regression(y_true: Sequence[float], y_pred: Sequence[float]) -> dict[str, float]:
    y = np.asarray(y_true, dtype=float)
    p = np.asarray(y_pred, dtype=float)
    errors = p - y
    return {
        "n": int(y.size),
        "mae": float(np.mean(np.abs(errors))),
        "rmse": float(np.sqrt(np.mean(errors ** 2))),
        "median_absolute_error": float(np.median(np.abs(errors))),
        "mean_error": float(np.mean(errors)),
    }


def interval(y_true: Sequence[float], lower: Sequence[float], upper: Sequence[float]) -> dict[str, float]:
    y = np.asarray(y_true, dtype=float)
    lo = np.asarray(lower, dtype=float)
    hi = np.asarray(upper, dtype=float)
    return {
        "coverage": float(np.mean((y >= lo) & (y <= hi))),
        "mean_width": float(np.mean(hi - lo)),
    }
