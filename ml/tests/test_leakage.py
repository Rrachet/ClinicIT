"""No information from after the prediction moment can reach the model.

The features themselves are computed "as of" the moment by the Spring Boot app, which is
tested against real PostgreSQL (WaitTimeFeatureBuilderIntegrationTest: later events never
change earlier features; WaitTimeDatasetBuilderIntegrationTest: later activity never changes
existing examples). These tests cover the Python side: what is used as input, and how time
is split.
"""

import numpy as np

from clinicit_ml.dataset import temporal_split
from clinicit_ml.features import FEATURE_NAMES, NOT_FEATURES, TARGET, to_matrix


def test_the_target_and_timing_columns_are_never_features():
    assert TARGET not in FEATURE_NAMES
    assert not NOT_FEATURES & set(FEATURE_NAMES)


def test_no_feature_is_derived_from_the_outcome():
    # Every input must be something known when a patient is waiting. Nothing about the
    # outcome of the visit (call time, consultation length, completion, no-show) is an input.
    forbidden_words = ("actual", "called_at", "completed_at", "outcome", "no_show", "future", "final")
    assert not [f for f in FEATURE_NAMES if any(w in f for w in forbidden_words)]


def test_the_matrix_ignores_extra_columns_even_if_they_leak(synthetic_rows):
    rows = synthetic_rows[:5]
    leaky = [dict(r, actual_wait_minutes=999.0, called_at="later") for r in rows]
    assert np.array_equal(to_matrix(rows), to_matrix(leaky), equal_nan=True)


def test_training_never_sees_validation_or_test_days(synthetic_rows):
    split = temporal_split(synthetic_rows)
    last_training_day = max(split.train_days)
    assert all(r["local_date"] > last_training_day for r in split.validation + split.test)


def test_the_model_uses_exactly_the_feature_columns(trained):
    model, _ = trained
    assert model.feature_names == FEATURE_NAMES
    assert model.point.n_features_in_ == len(FEATURE_NAMES)
