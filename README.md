# ClinicIT

A clinic operations platform for small clinics.

## Vision

ClinicIT manages the workflow from patient registration and appointment booking through queue management, doctor consultation, and patient notifications.

## Planned stack

- Java 21
- Spring Boot
- Spring Data JPA / Hibernate
- Spring Security
- PostgreSQL
- Redis
- WebSockets
- React / Next.js frontend
- Python ML service for later AI features
- Docker

## Development phases

1. Product and domain design
2. Spring Boot backend foundation
3. Authentication and roles
4. Appointment engine
5. Queue engine
6. Real-time updates
7. Patient experience and notifications
8. Analytics
9. AI-assisted operational intelligence
10. Testing, security, deployment, and production hardening

## Core roles

- Clinic Admin
- Receptionist
- Doctor
- Patient

## Core appointment lifecycle

BOOKED -> CONFIRMED -> ARRIVED -> WAITING -> CALLED -> IN_CONSULTATION -> COMPLETED

Alternative terminal paths include CANCELLED, NO_SHOW, and SKIPPED.

## Running tests

```bash
mvn test                          # backend
cd frontend && npm test           # frontend unit/component tests
```

Queue tests need real PostgreSQL (they rely on row locks, `SKIP LOCKED` and `ON CONFLICT`). They use
Testcontainers when Docker is available, or an existing database:

```bash
CLINICIT_TEST_DB_URL=jdbc:postgresql://localhost:5432/clinicit_test mvn test
```

With neither available they are reported as skipped.

## Running the full stack locally

Requirements: Java 21, Maven, Node 20.9+ and PostgreSQL.

**1. API** (http://localhost:8080). PostgreSQL at `localhost:5432/clinicit` by default; override with `DB_URL`,
`DB_USERNAME` and `DB_PASSWORD`. On an empty database the first clinic and admin are created from the
`CLINICIT_BOOTSTRAP_*` variables.

```bash
CLINICIT_CORS_ALLOWED_ORIGINS=http://localhost:3000 \
CLINICIT_BOOTSTRAP_CLINIC_NAME="City Clinic" \
CLINICIT_BOOTSTRAP_ADMIN_EMAIL=owner@cityclinic.example \
CLINICIT_BOOTSTRAP_ADMIN_PASSWORD='choose-a-long-password' \
mvn spring-boot:run
```

**2. Staff.** Sign in as the admin through the API, then create doctors and staff:

```bash
TOKEN=$(curl -s localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"owner@cityclinic.example","password":"choose-a-long-password"}' | jq -r .accessToken)
curl -s localhost:8080/api/v1/doctors -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"displayName":"Dr. Sharma"}'
curl -s localhost:8080/api/v1/users -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"email":"desk@cityclinic.example","fullName":"Front Desk","password":"a-long-desk-password","role":"RECEPTIONIST"}'
# A doctor login: role DOCTOR plus "doctorProfileId" from the doctor you created.
```

**3. Frontend** (http://localhost:3000):

```bash
cd frontend && cp .env.example .env.local && npm install && npm run dev
```

Sign in as the receptionist (reception console) or the doctor (doctor console). Patients open the status link that
reception gives them; they don't need an account.

**4. Everything end to end** in a real browser, against a real database:

```bash
DB_URL=jdbc:postgresql://localhost:5432/clinicit_e2e DB_USERNAME=postgres DB_PASSWORD=secret scripts/e2e.sh
```

## Design docs

- [Product spec](docs/PRODUCT_SPEC.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Data model](docs/DATA_MODEL.md)
- [API](docs/API.md)
- [Queue engine](docs/QUEUE_ENGINE.md)
- [Security: authentication, roles, clinic isolation](docs/SECURITY.md)
- [Real-time queue (WebSocket)](docs/REALTIME.md)
- [Frontend](docs/FRONTEND.md)

> ClinicIT is an operational system. AI features will assist clinic operations and will not make medical diagnoses or autonomous clinical decisions.
