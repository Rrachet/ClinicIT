# ClinicIT Architecture

## Overview

ClinicIT is a modular monolith: one Spring Boot application, one PostgreSQL database, and clear module boundaries
inside. The single exception is the optional Python wait-time model service, a separate process with no business
logic and no database access.

```
 Reception / Doctor / Admin screens        Patient status page (no login)
        (Next.js, frontend/)                       │ polls
              │ REST + STOMP WebSocket             │ public REST
              ▼                                    ▼
 ┌──────────────────────── Spring Boot API ─────────────────────────┐
 │ identity   clinic   patient   appointment   queue   realtime     │
 │ notification   history   analytics   prediction   common health  │
 └──────┬────────────────────────────────────────┬──────────────────┘
        │ JPA / JDBC, Flyway                     │ HTTP, 0.8 s, baseline fallback
        ▼                                        ▼
    PostgreSQL                          Python FastAPI (ml/)
 (all state, the outboxes,               trained scikit-learn model
  the immutable history)
        │ after commit
        ▼
 NotificationProvider port → development provider (an SMS/WhatsApp/email vendor adapter plugs in here)
```

## Backend modules

- identity: staff accounts, roles, login sessions, Spring Security configuration (see [SECURITY.md](SECURITY.md))
- clinic: clinics, doctor profiles, and clinic-local time (`ClinicTime`: "today" is always the clinic's day)
- patient: patient records and search
- appointment: booking and lifecycle
- queue: token generation and state transitions
- realtime: STOMP/WebSocket delivery of queue events and its security (see [REALTIME.md](REALTIME.md)); the queue module publishes through a port and never depends on it
- notification: patient messages. Listens to appointment/queue domain events, records messages in the causing transaction, delivers after commit through a `NotificationProvider` port (see [NOTIFICATIONS.md](NOTIFICATIONS.md)); core services never depend on it
- history: the immutable operational history (`operational_events`). The appointment and queue services append one event per transition inside the transition's transaction; nothing updates or deletes it (enforced by a database trigger)
- analytics: read-only operational metrics computed in PostgreSQL from that history, clinic-scoped and in clinic-local time (see [ANALYTICS.md](ANALYTICS.md))
- schedule: doctors' weekly hours, breaks, appointment length and time off (Phase 10), the booking rules the appointment module applies, and availability. Clinic-local wall-clock times throughout (see [SCHEDULING.md](SCHEDULING.md))
- prediction: estimated waits (Phase 8). Builds features "as of now" from the operational history, calls the Python ML service (`ml/`, FastAPI) through the `WaitTimeModelClient` port with a short timeout, and falls back to a deterministic baseline. Read-only; the queue and appointment modules never call it (see [AI.md](AI.md))
- common: error handling (one `ApiError` shape), shared exceptions, clock and scheduling configuration
- health: the public liveness endpoint
- ml/ (separate Python process): the trained wait-time model behind `POST /predict/wait-time`. It holds no business logic and has no database access

## Operations

- **One API instance.** The WebSocket broker is in-memory; everything else is already multi-instance safe
  ([OPERATIONS.md](OPERATIONS.md#websocket-scaling-boundary)).
- **Probes and metrics** (Spring Boot Actuator, Micrometer, Prometheus) run on an internal management port.
  Liveness never depends on PostgreSQL or the ML service; readiness includes PostgreSQL.
- **Configuration:** a `prod` profile that refuses unsafe settings at startup.
- **Logging:** structured JSON logs in production, a request id per request, and no patient data or secrets.
- **CI:** GitHub Actions runs the backend against PostgreSQL, the ML tests, the frontend checks and the browser E2E on
  every pull request ([TESTING.md](TESTING.md)).

## Technology choices

- Java 21, Spring Boot 3.5 (Web, Data JPA, Security with opaque-token resource server, WebSocket/STOMP, Actuator), Maven
- Micrometer with the Prometheus registry for metrics
- PostgreSQL 16 with Flyway. Integration tests run against real PostgreSQL, never an in-memory substitute.
- Next.js 16 / React 19 frontend
- Python 3.11, FastAPI and scikit-learn for the wait-time model
- JUnit 5 / Spring Boot Test, pytest, Vitest, Playwright

Deliberately not used: Redis, a message broker, a second database, microservices. The transactional outbox tables
and PostgreSQL row locks cover what those would otherwise be added for.

## Architectural principles

1. Business rules belong in application/domain services, not controllers.
2. Controllers remain thin.
3. Database constraints enforce invariants where practical.
4. State transitions are explicit.
5. Transactions surround queue mutations.
6. DTOs separate API contracts from persistence entities.
7. External notifications are behind interfaces/adapters.
8. AI is an isolated advisory capability and never becomes a source of medical truth.
9. Every important mutation should be auditable.
10. Prefer a modular monolith until real scale justifies services.
11. Every service operation receives the acting `Actor` and scopes its queries to `actor.clinicId()`; tenant boundaries are also enforced by composite foreign keys.
