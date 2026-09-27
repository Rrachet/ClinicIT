import sys
from pathlib import Path

import pytest

ML_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ML_ROOT))

from clinicit_ml import synthetic  # noqa: E402
from clinicit_ml.train import train  # noqa: E402


@pytest.fixture(scope="session")
def synthetic_rows():
    return synthetic.generate(days=40, rows_per_day=60, seed=7)


@pytest.fixture(scope="session")
def trained(synthetic_rows):
    """(model, report) trained on the small deterministic synthetic dataset."""
    return train(synthetic_rows, dataset_digest="synthetic-test", seed=1)
