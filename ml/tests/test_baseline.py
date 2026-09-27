"""The Python baseline matches the Java one, case for case."""

import json
from pathlib import Path

import pytest

from clinicit_ml import baseline

CASES = json.loads(Path("contracts/baseline_cases.json").read_text())["cases"]


def _row(features):
    return {
        "patients_ahead": features["patientsAhead"],
        "recent_consultation_minutes": features["recentConsultationMinutes"],
        "historical_consultation_minutes": features["historicalConsultationMinutes"],
    }


@pytest.mark.parametrize("case", CASES, ids=[c["name"] for c in CASES])
def test_matches_the_shared_contract(case):
    row = _row(case["features"])
    expected = case["expected"]
    assert baseline.minutes(row) == pytest.approx(expected["minutes"])
    assert baseline.estimate(row) == (expected["estimate"], expected["lower"], expected["upper"])


def test_rounds_halves_up_like_java_not_to_even():
    assert baseline.round_half_up(12.5) == 13
    assert baseline.round_half_up(11.5) == 12  # Python's round() would give 12 and 12 for 11.5/12.5


def test_version_is_stable():
    assert baseline.VERSION == "baseline-v1"
