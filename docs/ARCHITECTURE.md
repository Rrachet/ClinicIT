# ClinicIT Architecture

## Initial architecture

ClinicIT starts as a modular monolith. This keeps the first production-quality implementation understandable while preserving clear domain boundaries for future extraction.

```
Web / Mobile / Patient Status Page
              |
          REST + WebSocket
              |
       Spring Boot API
              |
   +----------+-----------+
   |          |           |
Identity   Scheduling   Queue
   |          |           |
   +----------+-----------+
              |
         PostgreSQL
              |
            Redis
              |
       Event / notification
              |
       External adapters
              |
        Future ML service
```

## Backend modules

- identity: staff accounts, roles, login sessions, Spring Security configuration (see [SECURITY.md](SECURITY.md))
- clinic: clinics, staff, doctor schedules
- patient: patient records and search
- appointment: booking and lifecycle
- queue: token generation and state transitions
- realtime: STOMP/WebSocket delivery of queue events and its security (see [REALTIME.md](REALTIME.md)); the queue module publishes through a port and never depends on it
- notification: notification intents and delivery adapters
- analytics: operational metrics
- ai: future model integration boundary

## Technology choices

- Java 21
- Spring Boot 3.5.x
- Spring Web
- Spring Data JPA
- Spring Security
- PostgreSQL
- Redis
- WebSocket/STOMP initially; implementation choice can be revisited after the MVP
- Maven
- JUnit 5 / Spring Boot Test
- Docker

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
