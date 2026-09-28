# AI: Wait-Time Prediction (Phase 8)

ClinicIT's first AI feature answers one operational question: **roughly how long will this patient wait before
being called?** Reception sees "Estimated wait: ~23 min" next to each waiting patient. The patient's status page
shows "Estimated wait 17–31 min".

This is **operational AI, not clinical AI.** The model predicts a waiting time from the state of the queue. It
does not know who the patient is or why they came, and it has no way to:
- diagnose, recommend treatment or prescribe;
- prioritise, triage or reorder patients;
- influence any clinical decision.

Estimates are advisory. Nothing in the queue reads them, and the call order stays strictly by token.

## Architecture

```
Browser ──► Spring Boot (modular monolith, unchanged business logic)
              │
              │  prediction module (read-only; never called by queue or appointment code)
              │    WaitTimePredictionService
              │      ├─ WaitTimeFeatureBuilder    features "as of now" from operational_events (SQL)
              │      ├─ cache per queue state      one ML call per queue change, not per request
              │      ├─ WaitTimeModelClient ──HTTP 0.8 s timeout──►  Python FastAPI (ml/)
              │      │                                                  POST /predict/wait-time
              │      │                                                  trained scikit-learn model
              │      └─ BaselineWaitTime          fallback on any failure, timeout or invalid answer
              ▼
        GET /api/v1/queues/today/wait-estimates        (staff; doctors: own queue)
        GET /api/v1/public/queue-status/{code}         (patient; range only)
```

- **Python does one thing: turn features into a number.** Features are computed in Spring Boot. Both the live
  predictions and the training dataset use the same SQL, so what the model is trained on and what it is given
  in production are identical by construction. No business logic moved to Python.
- **Infrastructure:** one extra small process. No Kubernetes, Kafka, feature store or second database; the
  model is a file loaded at start-up.
- **The ML service cannot hurt clinic operations.** Booking, check-in, calling, skipping and consultations
  never call it, and a test proves no request reaches it during queue operations. The service is used only when
  someone reads an estimate, with a 0.8-second budget. After a failure, Spring leaves it alone for 30 seconds
  and uses the baseline, so an outage costs one timeout rather than one per screen.

## Problem, target and prediction moments

**Target: `actual_wait_minutes`.** The time from the prediction moment to the patient's first call.

**Prediction moments ("snapshots"):**
- when the patient joins the queue, where the target is their full wait;
- every later moment, before their first call, when the number of patients ahead of them changes (someone ahead
  is called, skipped, or comes back). The target is then the wait remaining.

These are exactly the moments the product refreshes the estimate.

**Patients with no observed wait are left out.** A patient who was skipped or left before being called has no
wait to learn from. This biases the data slightly towards patients who stayed; see Limitations.

## Features

Every feature is computed by `WaitTimeFeatureBuilder` from `operational_events` with
`occurred_at <= asOf AND seq <= upToSeq`. The `seq` pin separates events that share a timestamp, such as two
patients checked in in the same second.

| Feature | Meaning | Why it is known at prediction time |
|---|---|---|
| `day_of_week` | clinic-local day, 1–7 | the clock |
| `minute_of_day` | clinic-local time of day | the clock |
| `patients_ahead` | waiting patients of the same doctor with a lower token | queue state from events up to now |
| `queue_length` | waiting patients of the doctor, including this one | same |
| `completed_today` | consultations the doctor has completed today | completions already recorded |
| `doctor_busy` | 1 if the doctor has a patient called or in consultation | same |
| `active_patient_minutes` | minutes since that patient was called | time since a past event |
| `calls_last_hour` | patients the doctor called in the last 60 minutes (pace) | past calls only |
| `recent_consultation_minutes` | mean of the doctor's last ≤3 consultations **completed** today | only finished consultations; the one in progress is not included |
| `historical_consultation_minutes` / `_count` | the doctor's mean consultation over the previous 28 days, and how many | previous days only |
| `historical_wait_minutes` | the doctor's mean wait over the previous 28 days, for patients who joined within ±1 hour of this time of day | previous days, and only waits that ended in a call |

**Never used:**
- Consultation durations, calls or completions after the moment.
- The future queue.
- No-show outcomes: a skip that later becomes a no-show is not known until it happens.
- Anything from the patient record: name, age, sex, phone, reason for visit.
- Identifiers (clinic, doctor, patient). A doctor is represented by their measured pace instead of an id, so
  what the model learns carries over between doctors. A doctor with no history yet gets the baseline.
- Appointment type: the domain doesn't model one yet.

Patient attributes were excluded on purpose even though some might correlate with the wait. They are not needed
to predict a queue, and using them would risk treating patients differently for who they are.

## Leakage prevention

| Guard | Where | Test |
|---|---|---|
| Features read only events at or before the moment, pinned to the triggering event | `WaitTimeFeatureBuilder` SQL | `laterEventsNeverChangeTheFeaturesOfAnEarlierMoment`, `aSnapshotCanBePinnedBetweenEventsWithTheSameTimestamp` |
| Nothing is read from current appointment or queue-entry state | same | the tests above add later events and compare |
| Existing dataset rows never change when history grows | `WaitTimeDatasetBuilder` | `laterActivityNeverChangesExamplesAlreadyInTheDataset` |
| The target and timing columns are never inputs | `features.py` | `test_leakage.py` |
| Split by whole days, in date order | `dataset.temporal_split` | `TestTemporalSplit` |
| Doctor history is from previous days only; waits only once they have ended | SQL | feature values in `featuresDescribeTheQueueAsItWasAtThatMoment` |

I also checked that these tests catch leaks. Removing the `occurred_at <= asOf` filter, removing the `seq` pin,
or splitting days randomly each makes tests fail.

## Dataset generation

**From real history:**

```bash
java -jar target/clinicit-*.jar --spring.main.web-application-type=none \
     --clinicit.prediction.dataset-export-path=wait_times.csv
```

This reads the history and writes one CSV row per snapshot. It has no identifiers, and rows are sorted by
time, so the same history always gives the same file.

**Synthetic history.** A real clinic's history doesn't exist yet, so the model was developed on a simulated
one. `SyntheticHistoryExport`, an opt-in test, runs a seeded discrete-event simulation *through the real
appointment and queue services*: one clinic, four doctors with 7/10/14/18-minute consultations, and 60 clinic
days from Monday to Saturday.

The simulation includes:
- a morning rush and heavier Mondays;
- log-normal consultations that get a little shorter when the room is full;
- occasional doctor breaks;
- 6% of called patients absent, most returning later.

Its history is exported by the same `WaitTimeDatasetBuilder`, producing `ml/data/synthetic_wait_times.csv.gz`
(23,004 rows).

```bash
mvn test -Dtest=SyntheticHistoryExport -Dclinicit.syntheticExport=$PWD/ml/data/synthetic_wait_times.csv
gzip -9 -n ml/data/synthetic_wait_times.csv     # about 5 minutes in all; same seed, same file
```

## Split

Whole clinic days, in date order. Nothing from a later day can influence training.

| Set | Days | Rows | Use |
|---|---|---|---|
| Train | 42 (5 Jan – 21 Feb 2026) | 16,197 | fitting |
| Validation | 9 (23 Feb – 4 Mar) | 3,561 | choosing the model, calibrating the interval |
| Test | 9 (5 – 14 Mar) | 3,246 | final evaluation, used once |

## Baseline

`patients ahead × average consultation length`, where the average comes from today's recent consultations,
else the doctor's 28-day history, else 10 minutes.

- **Two implementations kept identical:** Java (`BaselineWaitTime`, used as the fallback) and Python
  (`baseline.py`, used for evaluation). Both are tested against `ml/contracts/baseline_cases.json`, which
  includes a half-way case because Python's `round()` differs from Java's.
- **Fallback range:** half the estimate to 1.5× plus 5 minutes. It is a fixed rule, not a measured interval,
  and it is labelled `BASELINE` wherever it appears.

## Model

**Candidates:**
- gradient boosting (absolute-error loss);
- gradient boosting (squared-error loss);
- random forest.

All are scikit-learn, with no extra ML dependency. XGBoost wasn't needed: scikit-learn's histogram gradient
boosting is the same family of model.

**Selection:** each candidate is fitted on the training days and the lowest validation MAE wins. **The random
forest won** (150 trees, minimum 10 samples per leaf).

**Interval:** two gradient-boosting quantile models (10th and 90th percentile), then widened by conformalized
quantile regression so that 80% of validation waits fall inside. That added 5.3 minutes to each side.

**When the model is not used.** The service returns the baseline with a reason when:
- the doctor has fewer than 20 consultations in the previous 28 days (`INSUFFICIENT_HISTORY`);
- more patients are ahead than in any training example (`OUT_OF_TRAINING_RANGE`);
- no model is loaded (`MODEL_NOT_LOADED`).

## Evaluation (held-out test days, synthetic clinic)

From `ml/reports/wait_time_report.json`. A test retrains from the committed dataset and checks it reproduces
this report exactly.

| On 3,246 test snapshots | MAE | RMSE | Median abs. error | Mean error |
|---|---|---|---|---|
| **Model** (`wait-random-forest-512425ec89`) | **7.3 min** | 10.5 | 4.7 | −3.2 |
| Baseline | 17.8 min | 22.5 | 14.1 | −17.4 |
| Model, at check-in only (729) | 7.3 min | 10.8 | 5.2 | −1.8 |
| Baseline, at check-in only | 15.5 min | 21.7 | 11.1 | −14.4 |

**Validation MAE, used to choose:**

| Candidate | Validation MAE |
|---|---|
| Random forest | 6.24 |
| Gradient boosting (absolute error) | 6.39 |
| Gradient boosting (squared error) | 6.45 |
| Baseline | 16.67 |

**Interval (nominal 80%):**

| Set | Coverage | Mean width |
|---|---|---|
| Validation | 80.1% | — |
| Test | 75.6% | 21 min |
| Baseline rule on test | 40% | 27 min |

**What this shows, and what it doesn't:**
- **The baseline badly underestimates**, by 17 minutes on average. Consultation length is measured from start
  to completion, so it leaves out the patient already in the room and the time between patients. The model
  learns both.
- **The model roughly halves the error** on these held-out days. It still underestimates slightly, by 3 minutes
  on average.
- **The interval covers 76%, not 80%,** of held-out waits. It is a useful range, not a guarantee.
- **These figures are from a simulation.** They show that the pipeline works and how the model compares with
  the baseline *under the simulation's assumptions*. They are **not** evidence of accuracy in a real clinic.
  Before relying on the model, retrain and re-evaluate it on that clinic's own history. The report format and
  tests stay the same.

## Prediction API

The service contract is version `"1"`. Spring Boot sends features only, no identifiers.

```http
POST /predict/wait-time
{"schemaVersion": "1", "instances": [{"dayOfWeek": 2, "minuteOfDay": 605.0, "patientsAhead": 2, "queueLength": 3,
  "completedToday": 4, "doctorBusy": 1, "activePatientMinutes": 5.0, "callsLastHour": 3,
  "recentConsultationMinutes": 11.5, "historicalConsultationMinutes": 10.2, "historicalConsultationCount": 140,
  "historicalWaitMinutes": 24.0}]}

200 {"schemaVersion": "1", "modelVersion": "wait-random-forest-512425ec89",
     "predictions": [{"estimatedWaitMinutes": 23.4, "lowerBoundMinutes": 17.2, "upperBoundMinutes": 31.9,
                      "source": "MODEL", "reason": null}]}
```

- **Validation:** ranges and types; `patientsAhead < queueLength`; no busy minutes when the doctor is free;
  1–200 instances.
- **Unknown fields are rejected,** so patient data can't slip in.

| Error | Status | Code |
|---|---|---|
| Wrong schema version | 400 | `UNSUPPORTED_SCHEMA_VERSION` |
| Malformed JSON | 400 | `MALFORMED_JSON` |
| Anything else | 422 | `INVALID_REQUEST`, with the field names and never the submitted values |

`GET /health` reports whether a model is loaded and its version.

**ClinicIT API:**
- `GET /api/v1/queues/today/wait-estimates?doctorId=` (any staff; doctors get their own queue only). Returns,
  per waiting patient: `estimatedWaitMinutes`, `lowerBoundMinutes`, `upperBoundMinutes`, `source`,
  `modelVersion` and `fallbackReason`.
- The public status response gains `estimatedWait {estimatedWaitMinutes, lowerBoundMinutes, upperBoundMinutes}`
  while the patient is waiting. It is a range only, with no model details.

## Fallback behaviour

Spring Boot never shows an estimate it hasn't checked:

| Situation | Shown | `fallbackReason` |
|---|---|---|
| ML service not configured (`CLINICIT_ML_BASE_URL` empty) | baseline | `ML_DISABLED` |
| Service down, erroring, or slower than 0.8 s; then no retry for 30 s | baseline | `ML_UNAVAILABLE` |
| Answer invalid: wrong count or schema, missing version, negative, `lower > estimate`, over 24 h, unknown source | baseline | `INVALID_PREDICTION` |
| Service declines the row | baseline | `INSUFFICIENT_HISTORY` / `OUT_OF_TRAINING_RANGE` / `MODEL_NOT_LOADED` |
| Model answered | model | — |

A fallback caused by an outage is cached only until the service may be tried again, so estimates switch back to
the model soon after it recovers.

**Load:**
- **Server:** estimates are cached per patient and reused while the doctor's queue is unchanged (its latest
  history event), for up to 60 s. So the whole reception team and every patient's status page together cost at
  most one batched ML call per doctor per queue change.
- **Reception screen:** refetches 1.5 s after the queue signature changes (debounced, so a burst of live events
  costs one request) and once a minute while quiet.

## Metrics and failure handling in production

The ML service's failures are classified in `HttpWaitTimeModelClient` (timeout, unreachable, HTTP error, unreadable
response), and every call is measured:

| Metric | Meaning |
|---|---|
| `clinicit_ml_requests_seconds{outcome}` | latency; outcome `success`, `timeout`, `unreachable`, `http_error`, `bad_response`, `invalid_prediction` |
| `clinicit_ml_backoff_skips_total` | calls not made during the 30 s back-off after a failure |
| `clinicit_wait_estimates_total{source=MODEL\|BASELINE, reason}` | estimates produced; the BASELINE share is the **fallback rate** |
| `clinicit_wait_estimates_cache_total{result=hit\|miss}` | how much the per-queue-state cache saves |

Other safeguards:
- **Reasons from the service are filtered.** A fallback reason the ML service sends is passed on only if ClinicIT
  knows it; anything else becomes `OTHER`, so arbitrary text from the service never reaches the API or the metrics.
- **Nothing sensitive is logged:** only the failure kind, never the request.
- **The queue never depends on the ML service.** No queue or appointment operation calls it, and a test proves check-in,
  join, call-next, start, complete, skip and requeue all succeed without a single request reaching a hanging service.
- **Both health probes ignore it.** The ML service is in neither liveness nor readiness ([OPERATIONS.md](OPERATIONS.md)).

## Model versioning

- **Version format:** `wait-<algorithm>-<hash>`. The hash covers the dataset, the chosen algorithm, the
  hyperparameters, the seed, the minimum-history rule and the scikit-learn version. The same inputs give the
  same version; new data gives a new one.
- **Where the version appears:** in every ML response, in every staff estimate (`modelVersion`, shown in the
  reception tooltip) and in `/health`. Baseline estimates say `baseline-v1`.
- **Artifacts:** the trained model (`models/current/model.joblib`, not committed) is rebuilt with
  `python -m clinicit_ml.train`. Its `report.json` holds the evaluation that produced it.
- **Deploying a new model:** replace the file and restart the service. Spring picks up the new version on the
  next queue change.

## Limitations

- **Synthetic data only so far** (see Evaluation). The simulation's assumptions shape what the model learned.
- **Censoring:** patients who left before being called are excluded. They may have faced longer waits.
- **Correlated rows:** several snapshots come from one patient's wait, so the effective number of independent
  examples is smaller than the row count.
- **Under-coverage:** the interval under-covers slightly on the test days (76% against a nominal 80%), and the
  model underestimates by about 3 minutes on average.
- **Blind spots:** a doctor's unannounced break, an emergency, or a patient who takes much longer than usual
  cannot be foreseen from queue state.
- **Doctors without history** get the baseline until they have 20 consultations in the past 28 days.
- **No drift monitoring or automatic retraining yet.** Retrain as history accumulates, and compare with the
  baseline each time. The report and tests make that comparison routine.
- **No other appointment types:** the domain doesn't model them (e.g. follow-up vs new), so the model can't use
  them.

## Tests

| Suite | Covers |
|---|---|
| `WaitTimeFeatureBuilderIntegrationTest` | Hand-computed features; **later events never change earlier features**; same-instant pinning; empty history is null, not zero; **another clinic's activity is invisible** |
| `WaitTimeDatasetBuilderIntegrationTest` | One example per snapshot with correct targets, including skip, requeue and same-instant check-ins; **later activity never changes existing rows**; deterministic CSV without identifiers; per-clinic export |
| `WaitTimePredictionIntegrationTest` | Model answer used and labelled with its version; request carries features only; **service down → baseline with back-off**; **timeout under 1.5 s**; **8 kinds of invalid answer → baseline**; service declining a row; caching per queue state; new model version appears; the patient sees a range only while waiting; **clinic isolation (404) and doctor scoping (403)**; **queue operations never call the service** |
| `BaselineWaitTimeTest` + `test_baseline.py` | Java and Python baselines agree on the shared cases |
| `WaitTimeModelContractTest` + `test_api.py` | Spring's request matches the shared schema-1 example that the service accepts |
| `ml/tests/test_dataset.py`, `test_leakage.py` | Loading, the temporal split, the feature/target separation |
| `ml/tests/test_training.py` | Metrics on a hand-worked example; selection on validation; model beats baseline on the deterministic synthetic set; calibrated interval; model declines out-of-scope rows; **training is reproducible**; the committed report is reproduced exactly |
| `ml/tests/test_api.py` | Schema, order, fallback without a model, health and version, every error type, no echoing of submitted values |
| Frontend `waitEstimate.test.ts`, `ReceptionConsole.test.tsx`, `PatientStatus.test.tsx` | "~23 min" and "17–31 min" wording; estimates next to waiting patients; **one refetch per burst of queue events**; failure shows nothing and blocks nothing; the patient range and its "not an appointment time" note |
| Browser `wait-estimates.spec.ts` | The real ML service answers with its trained model; reception and the patient page show estimates end to end (Spring → FastAPI → baseline for a new doctor), and they update when the queue moves |

These tests fail if their safeguard is removed. I checked that by removing each in turn: the `asOf` filter, the
`seq` pin, response validation, the back-off, the read timeout, the cache, the day-ordered split, Java-style
rounding, rejection of unknown fields, and the minimum-history rule.
