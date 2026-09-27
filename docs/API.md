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

## Real-time events

Future WebSocket events:

- queue.updated
- patient.called
- appointment.updated
- notification.created
