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
- walk_in (Phase 10: booked for "now" at the desk; holds no slot)
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
- event_type: BOOKED, CONFIRMED, ARRIVED, WAITING (joined the queue), CALLED, IN_CONSULTATION, COMPLETED, CANCELLED, SKIPPED, REQUEUED, NO_SHOW, RESCHEDULED (Phase 10: a new time, status unchanged)
- previous_status
- occurred_at
- actor_user_id
- token_number, scheduled_at (minimal metadata; the new time for RESCHEDULED)

## Doctor schedules (Phase 10)

See [SCHEDULING.md](SCHEDULING.md) for the rules and the time model.

- `doctor_profiles.appointment_minutes`: slot length, 5–120 (default 15)
- `doctor_working_hours`: (doctor_id, day_of_week) primary key; start_time, end_time, optional break_start/break_end;
  check constraints keep start < end and the break inside the hours
- `doctor_time_off`: id, doctor_id, starts_at, ends_at (clinic-local, end exclusive), optional staff-only reason
- Both reference `doctor_profiles (id, clinic_id)`, so a schedule row can never point at another clinic's doctor

## Notification

Core fields:
- id, clinic_id, appointment_id, queue_entry_id
- type (APPOINTMENT_CONFIRMED, PATIENT_JOINED_QUEUE, PATIENT_CALLED, PATIENT_NEAR_TURN), channel, status (PENDING, SENT, FAILED)
- recipient (phone), body (fixed template, no clinical data)
- dedupe_key (unique: one message per meaningful change)
- attempts, max_attempts, next_attempt_at, expires_at, last_error
- provider, provider_message_id, created_at, sent_at, updated_at

Details: [NOTIFICATIONS.md](NOTIFICATIONS.md).

## Indexes

Reviewed in Phase 9 against each query, with `EXPLAIN ANALYZE` on 60 simulated clinic days (35,000 history events,
4,900 appointments, 13,700 notifications). Every online query uses an index; the slowest measured was the analytics
day window at 0.5 ms.

| Query | Index |
|---|---|
| Appointments for a clinic and day (reception list, analytics attendance) | `idx_appointment_clinic_schedule (clinic_id, scheduled_at)` |
| Appointments for a doctor and day | `idx_appointment_doctor_schedule (doctor_id, scheduled_at)` |
| Clinic-scoped lookups and foreign-key checks by `(id, clinic_id)` | `uk_appointment_id_clinic`, `uk_patient_id_clinic`, `uk_doctor_id_clinic` |
| Next waiting patient, "doctor busy?", patients ahead, a doctor's board | `idx_queue_doctor_date_status_token (doctor_id, queue_date, status, token_number)` |
| One active patient per doctor (constraint) | `uk_queue_one_active_per_doctor` (partial) |
| Public status page by code; entry by appointment | `uk_queue_status_code`, unique `appointment_id` |
| Token allocation | `queue_token_counters_pkey (clinic_id, queue_date)` |
| Analytics day and range windows | `idx_operational_events_clinic_time (clinic_id, occurred_at)` |
| Wait-time features and the estimate cache key | `idx_operational_events_doctor_time (doctor_id, occurred_at)` |
| An appointment's history, no-show/cancellation lookups | `idx_operational_events_appointment (appointment_id, seq)` |
| Notification claim (fast path and poller) | primary key; `idx_notification_due (next_attempt_at) WHERE status = 'PENDING'` |
| Messages for an appointment (front desk) | `idx_notification_clinic_appointment` |
| Notification dedupe | `uk_notification_dedupe` |
| Session lookup on every request; revoke all of a user's sessions | `uk_auth_session_token (token_hash)`, `idx_auth_session_user` |
| Real-time outbox poller and purge | `idx_queue_events_pending`, `idx_queue_events_published` (partial) |

**Deliberately without an index:**
- The dataset export's joins over the whole history: PostgreSQL hash-joins them (0.75 s for 60 days), and it is an
  offline job.
- The 6-hourly notification purge (`status <> 'PENDING' and updated_at < …`): a sequential scan of a table that
  30-day retention keeps small (18 ms at 13,700 rows). An index would cost a write on every notification update.
- Expired-session cleanup: sessions live 12 hours, so the table stays small.
- Patient search by name (a case-insensitive substring match) is limited to the clinic's rows by `idx_patient_clinic_name`. A trigram
  index would be the next step for large clinics.

`V11` dropped `idx_patient_clinic_phone` and `idx_notification_created`: no query uses them.
