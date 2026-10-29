# Contributing

Thanks for helping improve ClinicIT. This guide covers local setup and the conventions the project follows.

## Setup

- **Everything at once:** `docker compose up --build`, then open http://localhost:3000 (loads the demo clinic).
- **API:** Java 21 and PostgreSQL. `mvn spring-boot:run` with `CLINICIT_CORS_ALLOWED_ORIGINS=http://localhost:3000`
  and either the demo variables or `CLINICIT_BOOTSTRAP_*` for a first admin ([docs/OPERATIONS.md](docs/OPERATIONS.md)).
- **Web app:** Node.js 22. `cd frontend && npm ci && npm run dev`.
- **ML service (optional):** Python 3.11, see [ml/README.md](ml/README.md).

## Before you open a pull request

```bash
# Backend: the database name must contain "test" (every table is truncated)
CLINICIT_TEST_DB_URL=jdbc:postgresql://localhost:5432/clinicit_test CLINICIT_TEST_DB_USERNAME=postgres mvn verify

# Web app
cd frontend && npm test && npm run typecheck && npm run lint && npm run build

# A workflow changed? Run the browser tests on the real stack
DB_URL=jdbc:postgresql://localhost:5432/clinicit_e2e DB_USERNAME=postgres DB_PASSWORD=<password> scripts/e2e.sh
```

## Conventions

- **Clinic first.** Every query starts from the caller's clinic. New tables referencing clinic data use composite
  foreign keys with `clinic_id`.
- **Migrations** are append-only: add a new numbered file in `src/main/resources/db/migration`; never edit one that
  has been applied.
- **Endpoints** declare their roles. Public endpoints are rate limited and return no staff or patient details.
- **Operational, not clinical.** Nothing diagnoses, prescribes, reorders patients by condition or refuses care.
  Estimates are ranges and labelled as advisory.
- **Tests** run against real PostgreSQL; never skip or disable a test to get green.
- **No real patient data** in code, tests, screenshots or issues. The demo clinic is fictional.
- **Commits:** short imperative subject line, a body explaining why.
