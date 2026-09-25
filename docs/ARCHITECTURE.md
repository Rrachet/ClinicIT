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

- identity: users, roles, authentication boundaries
- clinic: clinics, staff, doctor schedules
- patient: patient records and search
- appointment: booking and lifecycle
- queue: token generation and state transitions
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
