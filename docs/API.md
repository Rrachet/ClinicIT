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

## Analytics (Phase 7)

Deterministic figures computed from the immutable operational history. See [ANALYTICS.md](ANALYTICS.md).
All GET, any staff role. The clinic comes from the session. `date` is clinic-local and defaults to today.
Doctors get only their own figures; another doctor's `doctorId` returns 403.

- GET  /api/v1/analytics/today?date=&doctorId=        day summary: patients, completed, avg/median wait, no-show rate, …
- GET  /api/v1/analytics/wait-times?date=&doctorId=   avg/median/p90/max wait, and per hour of call
- GET  /api/v1/analytics/doctors?date=                per doctor: patients handled, avg wait, avg consultation, utilization
- GET  /api/v1/analytics/queue?date=&doctorId=        current queue length; per hour: joined, completed, waiting
- GET  /api/v1/analytics/no-shows?from=&to=&doctorId= no-shows and cancellations for a range (≤ 366 days)

## Wait-time estimates (Phase 8)

Approximate, advisory waits before being called. See [AI.md](AI.md).

- GET  /api/v1/queues/today/wait-estimates?doctorId=   (any staff; doctors: own queue) per waiting patient:
                                                       estimatedWaitMinutes, lowerBoundMinutes, upperBoundMinutes,
                                                       source (MODEL | BASELINE), modelVersion, fallbackReason

The public queue status also returns `estimatedWait {estimatedWaitMinutes, lowerBoundMinutes, upperBoundMinutes}`
while the patient is waiting (null otherwise).

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
