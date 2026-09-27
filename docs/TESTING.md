# Testing

## Suites

| Suite | Tool | Where | What it needs |
|---|---|---|---|
| Backend unit and integration | JUnit 5, Spring Boot Test | `src/test/java` | **real PostgreSQL** for most tests |
| ML service | pytest | `ml/tests` | Python 3.11 |
| Frontend | Vitest, Testing Library | `frontend/src/**/*.test.ts(x)` | Node 22 |
| Browser end to end | Playwright | `frontend/e2e` | PostgreSQL, Java, Python, Node, Chromium |

### Why real PostgreSQL

The queue, the outboxes and the history rely on PostgreSQL behaviour: row locks (`FOR NO KEY UPDATE`,
`SKIP LOCKED`), `ON CONFLICT`, partial unique indexes, composite foreign keys, the append-only trigger and
`percentile_cont`. An in-memory database would not reproduce them, and once hid a real schema bug, so no test uses
one. Integration tests use the database in `CLINICIT_TEST_DB_URL` (its name must contain `test`, because every table
is truncated between tests), or a Testcontainers PostgreSQL when Docker is available. With neither, they are
**skipped**, so the unit tests still run anywhere. CI refuses skipped tests (below).

## Running locally

```bash
# Backend (needs PostgreSQL)
createdb clinicit_test
CLINICIT_TEST_DB_URL=jdbc:postgresql://localhost:5432/clinicit_test CLINICIT_TEST_DB_USERNAME=postgres mvn verify

# ML service
cd ml && python3 -m venv .venv && .venv/bin/pip install -r requirements-dev.txt && .venv/bin/python -m pytest

# Frontend
cd frontend && npm ci && npm test && npm run typecheck && npm run lint && npm run build

# Browser end to end: builds and starts the ML service, API and frontend against a throwaway database
createdb clinicit_e2e
DB_URL=jdbc:postgresql://localhost:5432/clinicit_e2e DB_USERNAME=postgres DB_PASSWORD=<password> scripts/e2e.sh
```

The backend tests run with the JVM in `America/Los_Angeles` while clinics are in `Asia/Kolkata`, so any accidental
use of the server's timezone fails a test.

## Continuous integration

`.github/workflows/ci.yml` runs on every pull request and every push to `main`. Any failing step fails the workflow.

| Job | Runs | Notes |
|---|---|---|
| `backend` | `mvn verify` against a **PostgreSQL 16 service container**, then `scripts/ci/check-test-reports.py` | the check fails the job if any test was skipped or none ran, so a broken database service cannot pass silently |
| `ml` | `pytest` | includes retraining on the committed dataset and checking it reproduces the committed report |
| `frontend` | `npm ci`, tests, typecheck, lint, production build | |
| `e2e` | `scripts/e2e.sh` with its own PostgreSQL service | runs after the three above pass |

The first three run in parallel. Maven, pip and npm caches are keyed on `pom.xml`, `ml/requirements*.txt` and
`frontend/package-lock.json`. A newer push cancels the running build of the same branch. On failure, the Surefire
reports and the Playwright results (with server logs) are uploaded as artifacts.

## What the important tests prove

| Area | Tests |
|---|---|
| Tenant isolation | `ClinicIsolationIntegrationTest`: clinic A's staff get 404 for every clinic B resource (patients, appointments, queue entries, doctors, users, notifications, analytics, wait estimates) and see none of it in listings; the database itself rejects cross-clinic references |
| Queue concurrency | `QueueConcurrencyIntegrationTest` (simultaneous joins, call-next, skip/start, `SKIP LOCKED`, check-in never blocked by call-next's lock); `QueueStressIntegrationTest` (12 workers, mixed operations including appointment-level no-shows and reads; tokens, one active patient per doctor, appointment/queue agreement and history all checked afterwards) |
| Notifications | `NotificationIntegrationTest`: retries with backoff, give-up, expiry, dedupe, rollback, **a crash mid-send still uses up an attempt**, **a crash after the vendor accepted is resent with the same idempotency key and delivered once**, a live lease is respected |
| Auth and sessions | `Authentication*`, `LoginThrottle*`, `PasswordChange*`, `UserManagement*`, `EndpointSecurityCoverageTest` (every `/api` endpoint needs a token and declares roles) |
| WebSocket security | `RealtimeSecurityIntegrationTest` |
| ML fallback | `WaitTimePredictionIntegrationTest`: service down, timeout, 8 kinds of invalid answer, back-off, cache, clinic scoping, queue operations never call the ML service, and the ML metrics |
| Production config | `ProductionConfigurationCheckTest`, including starting the real application with `prod` and no configuration |
| Health | `ObservabilityIntegrationTest` (probes and metrics on the internal port, nothing else exposed, request ids, metrics only after commit); `ProbesWithoutDatabaseTest` (database unreachable: liveness UP, readiness 503) |
| Log safety | `LogSafetyIntegrationTest`: a full flow logs no password, token, phone, name, reason, status code or message text; database errors carry no row contents |

Many of these were checked by **mutation**: the safeguard was removed, the test failed, then it was put back.
Examples: the claim-time attempt count, the `FOR NO KEY UPDATE` lock strength, `logServerErrorDetail`, the ML
timeout, back-off and cache, the history's `asOf` filter.

## What cannot be tested here

- **GitHub Actions itself** only runs on GitHub. The workflow was checked with `actionlint`, and every command in it
  was run locally.
- **Real notification vendors**: none is integrated; the development provider simulates outages and crashes.
- **Multi-instance behaviour**: ClinicIT is deployed as one instance ([OPERATIONS.md](OPERATIONS.md)).
- **Load at production scale**: the index review used 60 simulated clinic days (35,000 history events), not a
  multi-clinic, multi-year database.
