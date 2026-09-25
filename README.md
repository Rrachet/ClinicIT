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

> ClinicIT is an operational system. AI features will assist clinic operations and will not make medical diagnoses or autonomous clinical decisions.
