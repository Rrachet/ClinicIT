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
- queue_date
- token_number
- status
- checked_in_at
- called_at
- consultation_started_at
- completed_at
- created_at
- updated_at

Recommended constraint:
- UNIQUE(clinic_id, queue_date, token_number)

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
