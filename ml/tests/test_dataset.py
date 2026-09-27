"""Dataset loading and the temporal train/validation/test split."""

import gzip

import pytest

from clinicit_ml import synthetic
from clinicit_ml.dataset import load_csv, temporal_split
from clinicit_ml.features import FEATURE_NAMES, TARGET


def test_loads_the_exported_csv_with_types_and_missing_values(tmp_path, synthetic_rows):
    path = tmp_path / "wait.csv"
    synthetic.write_csv(synthetic_rows[:50], path)

    rows = load_csv(path)

    assert len(rows) == 50
    assert rows[0] == synthetic_rows[0]
    assert isinstance(rows[0]["patients_ahead"], int)
    assert any(r["recent_consultation_minutes"] is None for r in rows)


def test_loads_gzipped_files(tmp_path, synthetic_rows):
    plain = tmp_path / "wait.csv"
    synthetic.write_csv(synthetic_rows[:10], plain)
    zipped = tmp_path / "wait.csv.gz"
    zipped.write_bytes(gzip.compress(plain.read_bytes()))

    assert load_csv(zipped) == load_csv(plain)


def test_rejects_a_dataset_without_the_expected_columns(tmp_path):
    path = tmp_path / "bad.csv"
    path.write_text("local_date,patients_ahead\n2026-01-01,3\n")
    with pytest.raises(ValueError, match="missing columns"):
        load_csv(path)


def test_rejects_an_empty_required_feature(tmp_path, synthetic_rows):
    path = tmp_path / "wait.csv"
    row = dict(synthetic_rows[0], patients_ahead=None)
    synthetic.write_csv([row], path)
    with pytest.raises(ValueError, match="patients_ahead"):
        load_csv(path)


def test_the_committed_dataset_matches_the_feature_contract():
    rows = load_csv("data/synthetic_wait_times.csv.gz")
    assert len(rows) > 20_000
    assert all(r[TARGET] >= 0 for r in rows)
    assert all(0 <= r["patients_ahead"] < r["queue_length"] for r in rows)
    assert {r["snapshot"] for r in rows} == {"JOIN", "QUEUE_MOVED"}
    assert set(FEATURE_NAMES) <= set(rows[0])


class TestTemporalSplit:
    def test_whole_days_in_date_order_with_no_overlap(self, synthetic_rows):
        split = temporal_split(synthetic_rows)

        assert max(split.train_days) < min(split.validation_days)
        assert max(split.validation_days) < min(split.test_days)
        assert not set(split.train_days) & set(split.test_days)
        assert {r["local_date"] for r in split.train} == set(split.train_days)
        assert {r["local_date"] for r in split.test} == set(split.test_days)
        assert len(split.train) + len(split.validation) + len(split.test) == len(synthetic_rows)

    def test_proportions_are_by_day(self, synthetic_rows):
        split = temporal_split(synthetic_rows)  # 40 days
        assert (len(split.train_days), len(split.validation_days), len(split.test_days)) == (28, 6, 6)

    def test_input_order_does_not_matter(self, synthetic_rows):
        assert temporal_split(list(reversed(synthetic_rows))).test_days == temporal_split(synthetic_rows).test_days

    def test_needs_at_least_three_days(self, synthetic_rows):
        two_days = [r for r in synthetic_rows if r["local_date"] in {"2026-01-05", "2026-01-06"}]
        with pytest.raises(ValueError):
            temporal_split(two_days)

    def test_small_datasets_still_get_every_set(self, synthetic_rows):
        four_days = [r for r in synthetic_rows if r["local_date"] <= "2026-01-08"]
        split = temporal_split(four_days)
        assert split.train and split.validation and split.test
