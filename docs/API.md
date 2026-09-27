# ClinicIT API Roadmap

All endpoints except `POST /api/v1/auth/login` and `GET /api/v1/health` require
`Authorization: Bearer <token>`. The clinic is always the caller's; requests never carry a
`clinicId`. Roles per endpoint and error codes: [SECURITY.md](SECURITY.md).

## Auth and staff

- POST /api/v1/auth/login               (public) -> accessToken
- POST /api/v1/auth/logout
- GET  /api/v1/auth/me
- POST /api/v1/auth/password             change own password; revokes all own tokens
- POST /api/v1/users                    (admin) create staff account
- GET  /api/v1/users                    (admin)
- POST /api/v1/users/{id}/disable       (admin) also revokes the user's tokens
- POST /api/v1/doctors                  (admin) create doctor profile
- GET  /api/v1/doctors
- GET  /api/v1/clinic                   the caller's clinic, incl. its clinic-local `today`

## Patients

- POST /api/v1/patients
- GET /api/v1/patients/{id}
- GET /api/v1/patients?name=

## Appointments

- POST /api/v1/appointments
- GET /api/v1/appointments/{id}
- GET /api/v1/appointments?date=&doctorId=   (doctors: always their own)
- POST /api/v1/appointments/{id}/confirm
- POST /api/v1/appointments/{id}/cancel
- POST /api/v1/appointments/{id}/arrive
- POST /api/v1/appointments/{id}/no-show

## Queue

Implemented in Phase 2. See [QUEUE_ENGINE.md](QUEUE_ENGINE.md) for semantics, error codes and the reasons
for the changes from the original sketch.

- POST /api/v1/queue-entries            (body: appointmentId) join the queue, issue token
- GET  /api/v1/queue-entries/{id}
- POST /api/v1/queue-entries/{id}/start
- POST /api/v1/queue-entries/{id}/complete
- POST /api/v1/queue-entries/{id}/skip
- POST /api/v1/queue-entries/{id}/requeue
- POST /api/v1/queue-entries/{id}/no-show
- POST /api/v1/queues/call-next         (body: doctorId; optional for doctors)
- GET  /api/v1/queues/today?doctorId=     (optional for doctors)

## Patient status

- GET /api/v1/patient-status/{publicToken}

Patient status endpoints must use non-guessable public tokens and must not expose unnecessary patient information.

Appointment responses include `patientName`. Queue entry and board rows include `version`
(same as `entryVersion` in real-time events) and `statusCode` (the patient's status-link code).

## Notifications (Phase 6)

Patients are messaged automatically (confirmation, queue link, nearly your turn, your turn).
See [NOTIFICATIONS.md](NOTIFICATIONS.md).

- GET  /api/v1/notifications?appointmentId=   (front desk) messages for one appointment; recipient masked
- POST /api/v1/notifications/{id}/retry       (front desk) retry a FAILED message

## Patient status (public, no login)

- GET  /api/v1/public/queue-status/{code}   token, current token, patients ahead, status,
                                            clinic and doctor display name. No patient data.
                                            404 for unknown codes and after the queue day.

## Real-time (WebSocket)

STOMP over WebSocket at `/ws`; authenticate in the CONNECT frame with
`Authorization: Bearer <token>`. Read-only: clients subscribe, they never send.
Full contract, ordering rules and security: [REALTIME.md](REALTIME.md).

- SUBSCRIBE /topic/clinic/{clinicId}/queue                     (admin, receptionist of that clinic)
- SUBSCRIBE /topic/clinic/{clinicId}/doctor/{doctorId}/queue   (front desk of that clinic, or that doctor)

Event types: PATIENT_JOINED_QUEUE, PATIENT_CALLED, PATIENT_STARTED_CONSULTATION,
PATIENT_COMPLETED, PATIENT_SKIPPED, PATIENT_REQUEUED, PATIENT_NO_SHOW. Events carry ids and
queue state only (no patient data); load `GET /api/v1/queues/today` on connect/reconnect.

Future: `notification.created` (Phase 6).
