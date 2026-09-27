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
mvn test
```

Queue tests need real PostgreSQL (they rely on row locks, `SKIP LOCKED` and `ON CONFLICT`). They use
Testcontainers when Docker is available, or an existing database:

```bash
CLINICIT_TEST_DB_URL=jdbc:postgresql://localhost:5432/clinicit_test mvn test
```

With neither available they are reported as skipped.

## Running locally

```bash
# PostgreSQL at localhost:5432/clinicit (override with DB_URL / DB_USERNAME / DB_PASSWORD).
# On an empty database, create the first clinic and admin from environment variables:
CLINICIT_BOOTSTRAP_CLINIC_NAME="City Clinic" \
CLINICIT_BOOTSTRAP_ADMIN_EMAIL=owner@cityclinic.example \
CLINICIT_BOOTSTRAP_ADMIN_PASSWORD='choose-a-long-password' \
mvn spring-boot:run

curl -s localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"owner@cityclinic.example","password":"choose-a-long-password"}'
# then send: Authorization: Bearer <accessToken>
```

## Design docs

- [Product spec](docs/PRODUCT_SPEC.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Data model](docs/DATA_MODEL.md)
- [API](docs/API.md)
- [Queue engine](docs/QUEUE_ENGINE.md)
- [Security: authentication, roles, clinic isolation](docs/SECURITY.md)
- [Real-time queue (WebSocket)](docs/REALTIME.md)

> ClinicIT is an operational system. AI features will assist clinic operations and will not make medical diagnoses or autonomous clinical decisions.
