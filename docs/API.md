# ClinicIT API Roadmap

The exact routes will be finalized with the implementation, but the first API surface is:

## Patients

- POST /api/v1/patients
- GET /api/v1/patients/{id}
- GET /api/v1/patients?phone=&name=

## Appointments

- POST /api/v1/appointments
- GET /api/v1/appointments/{id}
- GET /api/v1/appointments?date=&doctorId=&status=
- POST /api/v1/appointments/{id}/confirm
- POST /api/v1/appointments/{id}/cancel
- POST /api/v1/appointments/{id}/arrive

## Queue

- GET /api/v1/queues/today?doctorId=
- POST /api/v1/queues/{appointmentId}/join
- POST /api/v1/queues/next/call
- POST /api/v1/queues/{queueEntryId}/start
- POST /api/v1/queues/{queueEntryId}/complete
- POST /api/v1/queues/{queueEntryId}/skip

## Patient status

- GET /api/v1/patient-status/{publicToken}

Patient status endpoints must use non-guessable public tokens and must not expose unnecessary patient information.

## Real-time events

Future WebSocket events:

- queue.updated
- patient.called
- appointment.updated
- notification.created
