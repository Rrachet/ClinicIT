# ClinicIT Data Model

Tables (Flyway migrations `V1`–`V10` in `src/main/resources/db/migration`):

| Table | Holds | Created in | More |
|---|---|---|---|
| `patients`, `appointments`, `queue_entries` | patients, appointments, queue entries | V1 (queue reworked in V3) | [QUEUE_ENGINE.md](QUEUE_ENGINE.md) |
| `clinics`, `doctor_profiles` | clinics, doctors | V2 | |
| `queue_token_counters` | per clinic/day token counter | V3 | [QUEUE_ENGINE.md](QUEUE_ENGINE.md) |
| `users`, `auth_sessions` | staff accounts, login sessions | V5 | [SECURITY.md](SECURITY.md) |
| `login_throttle` | brute-force protection | V6 | [SECURITY.md](SECURITY.md) |
| `queue_events` | real-time outbox, purged after 7 days | V7 | [REALTIME.md](REALTIME.md) |
| `notifications` | patient messages (outbox) | V9 | [NOTIFICATIONS.md](NOTIFICATIONS.md) |
| `operational_events` | immutable lifecycle history | V10 | [ANALYTICS.md](ANALYTICS.md) |

V4 added the clinic-scoped composite foreign keys, and V8 added the queue status-link code.

Relationships:

```
Clinic 1 --- N UserAccount 1 --- N AuthSession
Clinic 1 --- N DoctorProfile 0..1 --- 1 UserAccount (a DOCTOR login)
Clinic 1 --- N Patient
Patient 1 --- N Appointment
DoctorProfile 1 --- N Appointment
Appointment 1 --- 0..1 QueueEntry
Appointment 1 --- N Notification
Appointment 1 --- N OperationalEvent
```

Every clinic-owned row carries `clinic_id`. Composite foreign keys such as `(patient_id, clinic_id)` stop a row from
pointing at another clinic's data.

## Key identifiers

Use UUID primary keys for externally meaningful entities.

Queue token is a separate human-facing identifier such as `27`. It is scoped to a clinic/day.

## Appointment

Core fields:
- id
- clinic_id
- patient_id
- doctor_id
- scheduled_at
- status
- reason_summary
- created_at
- updated_at

## QueueEntry

Core fields:
- id
- appointment_id
- clinic_id
- doctor_id (copied from the appointment at join; the queue is served per doctor)
- queue_date (clinic-local date)
- token_number
- status
- checked_in_at
- called_at
- consultation_started_at
- completed_at
- skipped_at
- created_at
- updated_at
- version (optimistic locking)

Constraints:
- UNIQUE(clinic_id, queue_date, token_number)
- UNIQUE(appointment_id)
- partial UNIQUE(doctor_id, queue_date) WHERE status IN ('CALLED', 'IN_CONSULTATION')
- CHECK token_number > 0, CHECK status in the known set

## QueueTokenCounter

One row per clinic per day, `(clinic_id, queue_date) -> last_token`, incremented atomically when a patient
joins. See [QUEUE_ENGINE.md](QUEUE_ENGINE.md).

## Patient

Core fields:
- id
- clinic_id
- full_name
- phone
- date_of_birth (optional in MVP)
- created_at
- updated_at

Avoid collecting sensitive medical data until a specific clinical-record requirement is designed.

## Not modelled

There is no consultation record. ClinicIT tracks when a consultation starts and ends, not what happens in it.
Clinical data would need stronger access controls, auditability, retention rules and a privacy review first.

## OperationalEvent

The immutable history of every appointment and queue transition (Phase 7, table `operational_events`, see
[ANALYTICS.md](ANALYTICS.md)). It is append-only: a trigger rejects UPDATE and DELETE.

Core fields:
- id, seq
- clinic_id, appointment_id, queue_entry_id (queue events), doctor_id, patient_id (ids only)
- event_type: BOOKED, CONFIRMED, ARRIVED, WAITING (joined the queue), CALLED, IN_CONSULTATION, COMPLETED, CANCELLED, SKIPPED, REQUEUED, NO_SHOW
- previous_status
- occurred_at
- actor_user_id
- token_number, scheduled_at (minimal metadata)

## Notification

Core fields:
- id, clinic_id, appointment_id, queue_entry_id
- type (APPOINTMENT_CONFIRMED, PATIENT_JOINED_QUEUE, PATIENT_CALLED, PATIENT_NEAR_TURN), channel, status (PENDING, SENT, FAILED)
- recipient (phone), body (fixed template, no clinical data)
- dedupe_key (unique: one message per meaningful change)
- attempts, max_attempts, next_attempt_at, expires_at, last_error
- provider, provider_message_id, created_at, sent_at, updated_at

Details: [NOTIFICATIONS.md](NOTIFICATIONS.md).
