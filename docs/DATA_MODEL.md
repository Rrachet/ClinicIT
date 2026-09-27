# ClinicIT Data Model

Initial entities:

- Clinic
- User
- DoctorProfile
- Patient
- Appointment
- QueueEntry
- Notification
- Consultation

Relationships:

```
Clinic 1 --- N User
Clinic 1 --- N DoctorProfile
Clinic 1 --- N Patient
Patient 1 --- N Appointment
DoctorProfile 1 --- N Appointment
Appointment 1 --- 0..1 QueueEntry
Appointment 1 --- 0..1 Consultation
Appointment 1 --- N Notification
```

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

## Consultation

Later-phase fields should be intentionally scoped. Clinical data requires stronger access controls, auditability, retention rules, and privacy review.

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
- id
- appointment_id
- channel
- type
- status
- recipient
- payload/reference
- created_at
- sent_at
