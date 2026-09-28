# ClinicIT

A clinic operations platform for small clinics.

## Vision

ClinicIT manages the workflow from patient registration and appointment booking through queue management, doctor consultation, and patient notifications.

## Stack

- **Backend:** Java 21, Spring Boot 3.5 (Web, Data JPA/Hibernate, Security, WebSocket/STOMP), a modular
  monolith. PostgreSQL 16 with Flyway migrations.
- **Frontend:** Next.js 16 / React 19 (`frontend/`).
- **ML:** a Python 3.11 FastAPI service with scikit-learn for wait-time estimates (`ml/`). It is optional; without
  it ClinicIT uses a deterministic estimate.
- **Tests:** JUnit 5 against real PostgreSQL, pytest, Vitest and Testing Library, and Playwright end to end.

There is no Redis, message broker or second database. PostgreSQL is the only store.

## What's built

| Phase | | Docs |
|---|---|---|
| 0 | Foundation and architecture | [Architecture](docs/ARCHITECTURE.md), [Product spec](docs/PRODUCT_SPEC.md) |
| 1 | Patients and appointments | [Data model](docs/DATA_MODEL.md), [API](docs/API.md) |
| 2 | Queue engine: tokens, call next, concurrency | [Queue engine](docs/QUEUE_ENGINE.md) |
| 3 | Authentication, roles, clinic isolation, security hardening | [Security](docs/SECURITY.md) |
| 4 | Real-time queue over authenticated WebSockets | [Real-time](docs/REALTIME.md) |
| 5 | Frontend: reception, doctor and patient-status screens | [Frontend](docs/FRONTEND.md) |
| 6 | Patient notifications (provider-agnostic outbox) | [Notifications](docs/NOTIFICATIONS.md) |
| 7 | Immutable operational history and analytics dashboard | [Analytics](docs/ANALYTICS.md) |
| 8 | AI wait-time prediction (advisory, operational only) | [AI](docs/AI.md), [`ml/`](ml/README.md) |
| 9 | CI/CD and production hardening: probes, metrics, safe configuration, reliability fixes | [Operations](docs/OPERATIONS.md), [Testing](docs/TESTING.md) |
| 10 | Doctor scheduling: weekly hours, breaks, leave, slots, walk-ins, rescheduling | [Scheduling](docs/SCHEDULING.md) |

## Core roles

- Clinic Admin (everything reception can do, plus staff, doctors and analytics)
- Receptionist
- Doctor (own appointments and queue only)
- Patient (no account: follows their place in the queue through a private status link)

## Core appointment lifecycle

BOOKED -> CONFIRMED -> ARRIVED -> WAITING -> CALLED -> IN_CONSULTATION -> COMPLETED

Other paths: BOOKED/CONFIRMED → CANCELLED; CONFIRMED/ARRIVED → NO_SHOW; WAITING/CALLED → SKIPPED, then back to
WAITING or on to NO_SHOW. COMPLETED, CANCELLED and NO_SHOW are final. See [docs/QUEUE_ENGINE.md](docs/QUEUE_ENGINE.md).

## Running tests

```bash
# Backend: needs real PostgreSQL (row locks, SKIP LOCKED, ON CONFLICT, triggers)
CLINICIT_TEST_DB_URL=jdbc:postgresql://localhost:5432/clinicit_test mvn test

# ML service
cd ml && python3 -m venv .venv && .venv/bin/pip install -r requirements-dev.txt && .venv/bin/python -m pytest

# Frontend
cd frontend && npm ci && npm test && npm run typecheck && npm run lint && npm run build
```

CI (GitHub Actions, `.github/workflows/ci.yml`) runs all of these, plus the browser E2E, on every pull request and
push to `main`, with PostgreSQL as a service container. Details: [docs/TESTING.md](docs/TESTING.md).

The backend tests use the database in `CLINICIT_TEST_DB_URL` (its name must contain `test`, because every table
is truncated). Without it they use Testcontainers when Docker is available. With neither, they are reported as
skipped.

## Running the full stack locally

### With Docker (quickest)

```bash
docker compose up --build
```

Then open http://localhost:3000. The first start loads the **demo clinic** ([docs/DEMO.md](docs/DEMO.md)):
ClinicIT Demo Clinic, Hyderabad, with three doctors, 18 fictional patients, two weeks of history and today's queue
in every state.

| Sign in as | Email | Password |
|---|---|---|
| Admin | `admin@demo.clinicit.local` | `local-demo-only-2026` |
| Receptionist | `reception@demo.clinicit.local` | same |
| Doctor | `ananya.reddy@demo.clinicit.local` (also `farhan.siddiqui@…`, `kavya.iyer@…`) | same |

These are local demo credentials, published on purpose; change the password with `CLINICIT_DEMO_PASSWORD` in a
`.env` file before the first start. Compose starts PostgreSQL, the API, the wait-time ML service and the web app, all
bound to 127.0.0.1. It is for evaluation only; production is described in [docs/OPERATIONS.md](docs/OPERATIONS.md).
`docker compose down -v` removes everything, including the database.

### Without Docker

Requirements: Java 21, Maven, Node 20.9+, PostgreSQL, and Python 3.11+ for the optional ML service.

**1. API** (http://localhost:8080). PostgreSQL at `localhost:5432/clinicit` by default; override with `DB_URL`,
`DB_USERNAME` and `DB_PASSWORD`. On an empty database the first clinic and admin are created from the
`CLINICIT_BOOTSTRAP_*` variables.

```bash
CLINICIT_CORS_ALLOWED_ORIGINS=http://localhost:3000 \
CLINICIT_PUBLIC_APP_URL=http://localhost:3000 \
CLINICIT_BOOTSTRAP_CLINIC_NAME="City Clinic" \
CLINICIT_BOOTSTRAP_ADMIN_EMAIL=owner@cityclinic.example \
CLINICIT_BOOTSTRAP_ADMIN_PASSWORD='choose-a-long-password' \
mvn spring-boot:run
```

**2. Wait-time model (optional).** Without it, estimates come from the deterministic baseline.

```bash
cd ml && python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
.venv/bin/python -m clinicit_ml.train --data data/synthetic_wait_times.csv.gz --out models/current
CLINICIT_MODEL_DIR=models/current .venv/bin/uvicorn clinicit_ml.api:create_default_app --factory --port 8000
```

Then start the API with `CLINICIT_ML_BASE_URL=http://localhost:8000`.

**3. Staff.** Sign in as the admin through the API, then create doctors and staff:

```bash
TOKEN=$(curl -s localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"owner@cityclinic.example","password":"choose-a-long-password"}' | jq -r .accessToken)
curl -s localhost:8080/api/v1/doctors -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"displayName":"Dr. Sharma"}'
curl -s localhost:8080/api/v1/users -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"email":"desk@cityclinic.example","fullName":"Front Desk","password":"a-long-desk-password","role":"RECEPTIONIST"}'
# A doctor login: role DOCTOR plus "doctorProfileId" from the doctor you created.
```

**4. Frontend** (http://localhost:3000):

```bash
cd frontend && cp .env.example .env.local && npm install && npm run dev
```

Sign in as the receptionist (reception console), the doctor (doctor console) or the admin (reception, plus the
Analytics dashboard). Patients are messaged their queue
status link automatically when they check in; they don't need an account. By default the development notification
provider records messages instead of sending them (see [docs/NOTIFICATIONS.md](docs/NOTIFICATIONS.md)).

**5. Everything end to end** in a real browser, against a real database. The script builds and starts the ML
service, the API and the frontend, and seeds its own staff:

```bash
DB_URL=jdbc:postgresql://localhost:5432/clinicit_e2e DB_USERNAME=postgres DB_PASSWORD=secret scripts/e2e.sh
```

## Running in production

Set `SPRING_PROFILES_ACTIVE=prod` and the variables in [`.env.example`](.env.example). The app refuses to start with
missing or unsafe settings. Health probes and Prometheus metrics are on the internal port 8081. See
[docs/OPERATIONS.md](docs/OPERATIONS.md).

## Design docs

- [Product spec](docs/PRODUCT_SPEC.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Data model](docs/DATA_MODEL.md)
- [API](docs/API.md)
- [Queue engine](docs/QUEUE_ENGINE.md)
- [Security: authentication, roles, clinic isolation](docs/SECURITY.md)
- [Real-time queue (WebSocket)](docs/REALTIME.md)
- [Frontend](docs/FRONTEND.md)
- [Patient notifications](docs/NOTIFICATIONS.md)
- [Operational analytics](docs/ANALYTICS.md)
- [AI: wait-time prediction](docs/AI.md) (the ML service is in [`ml/`](ml/README.md))
- [Operations and deployment](docs/OPERATIONS.md)
- [Testing and CI](docs/TESTING.md)

> ClinicIT is an operational system. Its one AI feature estimates waiting time from the state of the queue. It is
> advisory and never diagnoses, prescribes, prioritises patients or makes clinical decisions (see
> [docs/AI.md](docs/AI.md)).
