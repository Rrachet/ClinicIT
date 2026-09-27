# ClinicIT wait-time service

A small FastAPI service that estimates how long a waiting patient will wait before being called. It is
operational and advisory only; see [docs/AI.md](../docs/AI.md) for the design, the data, the evaluation and its
limits.

```bash
cd ml
python3 -m venv .venv && .venv/bin/pip install -r requirements-dev.txt

# Train from the committed synthetic dataset (about 10 s; reproducible)
.venv/bin/python -m clinicit_ml.train --data data/synthetic_wait_times.csv.gz --out models/current

# Serve
CLINICIT_MODEL_DIR=models/current .venv/bin/uvicorn clinicit_ml.api:create_default_app --factory --port 8000

# Test
.venv/bin/python -m pytest
```

Then point ClinicIT at it with `CLINICIT_ML_BASE_URL=http://localhost:8000`. Without that variable, or whenever
the service is unreachable, ClinicIT shows the deterministic baseline instead.

| Path | |
|---|---|
| `clinicit_ml/features.py` | the 12 inputs (must match `WaitTimeFeatures` in Spring Boot) |
| `clinicit_ml/dataset.py` | loading the exported CSV and the temporal train/validation/test split |
| `clinicit_ml/baseline.py` | the deterministic baseline (same as `BaselineWaitTime.java`) |
| `clinicit_ml/train.py` | model selection, calibrated intervals, held-out evaluation, versioning |
| `clinicit_ml/model.py` | the trained model and when it may be used |
| `clinicit_ml/api.py` | `POST /predict/wait-time` (schema version "1") and `GET /health` |
| `clinicit_ml/synthetic.py` | a tiny deterministic dataset for unit tests |
| `contracts/` | examples shared with the Java tests (baseline cases, request) |
| `data/synthetic_wait_times.csv.gz` | the simulated clinic history (see docs/AI.md) |
| `reports/wait_time_report.json` | the evaluation of the model trained on it |

To train on a real clinic's history, export it from ClinicIT
(`--clinicit.prediction.dataset-export-path=wait_times.csv`) and pass that file to `--data`.
