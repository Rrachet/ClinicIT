"""Model selection, evaluation and reproducibility."""

import json
from pathlib import Path

import pytest

from clinicit_ml import metrics
from clinicit_ml.dataset import load_csv
from clinicit_ml.train import file_digest, train


def test_metrics_on_a_hand_worked_example():
    m = metrics.regression([10, 20, 30, 40], [12, 18, 30, 50])
    assert m["mae"] == pytest.approx(3.5)            # (2 + 2 + 0 + 10) / 4
    assert m["rmse"] == pytest.approx((108 / 4) ** 0.5)
    assert m["median_absolute_error"] == pytest.approx(2.0)
    assert m["mean_error"] == pytest.approx(2.5)
    assert metrics.interval([10, 20, 30, 40], [5, 21, 25, 30], [15, 25, 35, 39]) == {
        "coverage": 0.5, "mean_width": 8.25}


def test_the_model_is_chosen_on_validation_and_compared_with_the_baseline_on_test(trained):
    model, report = trained
    selection = report["model_selection_on_validation"]

    assert set(selection) == {"hgb_absolute_error", "hgb_squared_error", "random_forest", "baseline"}
    assert report["algorithm"] == min(
        (name for name in selection if name != "baseline"), key=lambda n: selection[n]["mae"])
    assert model.version.startswith("wait-")
    # On this synthetic data the model can learn what the baseline ignores (the patient in the room).
    assert report["test"]["model"]["mae"] < report["test"]["baseline"]["mae"]
    assert report["test"]["model"]["n"] == report["test"]["baseline"]["n"]


def test_the_interval_is_calibrated_on_validation(trained):
    _, report = trained
    intervals = report["intervals"]
    assert intervals["nominal_coverage"] == 0.8
    assert intervals["validation"]["coverage"] >= 0.8
    assert 0.6 <= intervals["test_model"]["coverage"] <= 1.0


def test_predictions_respect_zero_lower_point_upper(trained, synthetic_rows):
    model, _ = trained
    for p in model.predict(synthetic_rows[-200:]):
        assert 0 <= p.lower_bound_minutes <= p.estimated_wait_minutes <= p.upper_bound_minutes


def test_the_model_declines_rows_without_enough_doctor_history(trained, synthetic_rows):
    model, _ = trained
    row = dict(synthetic_rows[-1], historical_consultation_count=0, historical_consultation_minutes=None)
    [prediction] = model.predict([row])
    assert prediction.source == "BASELINE"
    assert prediction.reason == "INSUFFICIENT_HISTORY"

    far = dict(synthetic_rows[-1], patients_ahead=model.max_patients_ahead + 1, queue_length=model.max_patients_ahead + 2)
    assert model.predict([far])[0].reason == "OUT_OF_TRAINING_RANGE"


def test_training_is_reproducible(synthetic_rows, trained):
    model, report = trained
    again_model, again_report = train(synthetic_rows, dataset_digest="synthetic-test", seed=1)
    assert again_model.version == model.version
    assert again_report == report


def test_different_data_gives_a_different_model_version(synthetic_rows, trained):
    model, _ = trained
    other, _ = train(synthetic_rows, dataset_digest="another-dataset", seed=1)
    assert other.version != model.version


def test_the_committed_report_is_what_the_committed_dataset_produces():
    """The figures quoted in docs/AI.md come from this report; retraining must reproduce them."""
    data = "data/synthetic_wait_times.csv.gz"
    _, report = train(load_csv(data), file_digest(data))
    committed = json.loads(Path("reports/wait_time_report.json").read_text())
    assert report == committed
