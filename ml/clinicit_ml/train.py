"""Train, select and evaluate the wait-time model.

    python -m clinicit_ml.train --data data/synthetic_wait_times.csv.gz --out models/current

1. Temporal split by clinic day (dataset.temporal_split): train → validation → test.
2. Baseline: patients ahead × average consultation length (baseline.py).
3. Candidates, each fitted on the training days only: gradient boosting with absolute-error
   loss, gradient boosting with squared-error loss, and a random forest. The one with the
   lowest mean absolute error on the validation days is chosen. The test days play no part.
4. Interval: gradient-boosting quantile models for the 10th and 90th percentile, then
   widened by conformalized quantile regression on the validation days so that about 80% of
   validation waits fall inside. The same adjustment is then checked on the test days.
5. Evaluation on the test days, once: the model and the baseline on the same rows.

Everything is seeded; the same data gives the same model version and the same report.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path
from typing import Any

import numpy as np
import sklearn
from sklearn.ensemble import HistGradientBoostingRegressor, RandomForestRegressor

from . import baseline, metrics
from .dataset import Row, load_csv, temporal_split
from .features import FEATURE_NAMES, TARGET, to_matrix
from .model import WaitTimeModel

MIN_HISTORY = 20            # consultations in the doctor's previous 28 days before the model is trusted
INTERVAL = (0.10, 0.90)     # nominal 80% range
SEED = 20260105

HGB_PARAMS = dict(learning_rate=0.05, max_iter=300, max_leaf_nodes=31, min_samples_leaf=40,
                  l2_regularization=1.0, early_stopping=False)


def candidates(seed: int) -> dict[str, Any]:
    return {
        "hgb_absolute_error": HistGradientBoostingRegressor(loss="absolute_error", random_state=seed, **HGB_PARAMS),
        "hgb_squared_error": HistGradientBoostingRegressor(loss="squared_error", random_state=seed, **HGB_PARAMS),
        "random_forest": RandomForestRegressor(n_estimators=150, min_samples_leaf=10, max_features=0.5,
                                               random_state=seed, n_jobs=-1),
    }


def _targets(rows: list[Row]) -> np.ndarray:
    return np.array([row[TARGET] for row in rows], dtype=float)


def _applicable(rows: list[Row], max_ahead: int) -> list[Row]:
    return [r for r in rows if r["historical_consultation_count"] >= MIN_HISTORY and r["patients_ahead"] <= max_ahead]


def _round(value: Any) -> Any:
    if isinstance(value, float):
        return round(value, 3)
    if isinstance(value, dict):
        return {k: _round(v) for k, v in value.items()}
    if isinstance(value, list):
        return [_round(v) for v in value]
    return value


def train(rows: list[Row], dataset_digest: str, seed: int = SEED) -> tuple[WaitTimeModel, dict[str, Any]]:
    split = temporal_split(rows)
    max_ahead = max(r["patients_ahead"] for r in split.train)
    x_train, y_train = to_matrix(split.train), _targets(split.train)

    validation = _applicable(split.validation, max_ahead)
    test = _applicable(split.test, max_ahead)
    if not validation or not test:
        raise ValueError("not enough rows with doctor history in validation/test to evaluate a model")
    x_val, y_val = to_matrix(validation), _targets(validation)

    # 3. Model selection on validation days only.
    selection: dict[str, dict[str, float]] = {}
    fitted: dict[str, Any] = {}
    for name, estimator in candidates(seed).items():
        estimator.fit(x_train, y_train)
        fitted[name] = estimator
        selection[name] = metrics.regression(y_val, np.maximum(estimator.predict(x_val), 0))
    selection["baseline"] = metrics.regression(y_val, [baseline.minutes(r) for r in validation])
    chosen = min(fitted, key=lambda n: selection[n]["mae"])

    # 4. Quantile models + conformal calibration on validation days.
    lower = HistGradientBoostingRegressor(loss="quantile", quantile=INTERVAL[0], random_state=seed, **HGB_PARAMS)
    upper = HistGradientBoostingRegressor(loss="quantile", quantile=INTERVAL[1], random_state=seed, **HGB_PARAMS)
    lower.fit(x_train, y_train)
    upper.fit(x_train, y_train)
    scores = np.maximum(lower.predict(x_val) - y_val, y_val - upper.predict(x_val))
    level = min(1.0, math.ceil((len(scores) + 1) * (INTERVAL[1] - INTERVAL[0])) / len(scores))
    adjustment = float(np.quantile(scores, level, method="higher"))

    digest = hashlib.sha256(json.dumps({
        "dataset": dataset_digest, "chosen": chosen, "hgb": HGB_PARAMS, "seed": seed,
        "min_history": MIN_HISTORY, "sklearn": sklearn.__version__,
    }, sort_keys=True).encode()).hexdigest()[:10]
    model = WaitTimeModel(
        version=f"wait-{chosen.replace('_', '-')}-{digest}",
        algorithm=chosen, point=fitted[chosen], lower=lower, upper=upper,
        interval_adjustment=adjustment, min_history=MIN_HISTORY, max_patients_ahead=max_ahead)

    # 5. Held-out evaluation on the test days: model and baseline on the same rows.
    x_test, y_test = to_matrix(test), _targets(test)
    point, lo, hi = model.raw_intervals(x_test)
    base = [baseline.estimate(r) for r in test]
    join = [i for i, r in enumerate(test) if r.get("snapshot") == "JOIN"]
    all_test_y = _targets(split.test)
    served = [p.estimated_wait_minutes for p in model.predict(split.test)]

    report = {
        "model_version": model.version,
        "algorithm": chosen,
        "features": FEATURE_NAMES,
        "target": TARGET,
        "split": split.describe(),
        "applicability": {"min_history_consultations": MIN_HISTORY, "max_patients_ahead": max_ahead,
                          "test_rows_where_model_applies": len(test), "test_rows": len(split.test)},
        "model_selection_on_validation": selection,
        "test": {
            "model": metrics.regression(y_test, point),
            "baseline": metrics.regression(y_test, [b[0] for b in base]),
            "model_at_check_in": metrics.regression(y_test[join], point[join]),
            "baseline_at_check_in": metrics.regression(y_test[join], [base[i][0] for i in join]),
            "served_all_test_rows": metrics.regression(all_test_y, served),
            "baseline_all_test_rows": metrics.regression(all_test_y, [baseline.minutes(r) for r in split.test]),
        },
        "intervals": {
            "nominal_coverage": round(INTERVAL[1] - INTERVAL[0], 2),
            "conformal_adjustment_minutes": adjustment,
            "validation": metrics.interval(y_val, *model.raw_intervals(x_val)[1:]),
            "test_model": metrics.interval(y_test, lo, hi),
            "test_baseline_rule": metrics.interval(y_test, [b[1] for b in base], [b[2] for b in base]),
        },
        "reproducibility": {"seed": seed, "dataset_sha256": dataset_digest, "scikit_learn": sklearn.__version__,
                            "numpy": np.__version__},
    }
    report = _round(report)
    model.metadata = report
    return model, report


def file_digest(path: str | Path) -> str:
    """Digest of the decompressed dataset, so .csv and .csv.gz of the same data agree."""
    import gzip

    path = Path(path)
    opener = gzip.open if path.suffix == ".gz" else open
    with opener(path, "rb") as handle:
        return hashlib.sha256(handle.read()).hexdigest()


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--data", default="data/synthetic_wait_times.csv.gz")
    parser.add_argument("--out", default="models/current")
    parser.add_argument("--report", default=None, help="also write the report JSON here")
    args = parser.parse_args(argv)

    rows = load_csv(args.data)
    model, report = train(rows, file_digest(args.data))
    model.save(args.out)
    text = json.dumps(report, indent=2, sort_keys=False) + "\n"
    Path(args.out, "report.json").write_text(text)
    if args.report:
        Path(args.report).parent.mkdir(parents=True, exist_ok=True)
        Path(args.report).write_text(text)
    t = report["test"]
    print(f"{model.version}: test MAE {t['model']['mae']:.2f} min (baseline {t['baseline']['mae']:.2f}), "
          f"80% interval coverage {report['intervals']['test_model']['coverage']:.2f}")


if __name__ == "__main__":
    main()
