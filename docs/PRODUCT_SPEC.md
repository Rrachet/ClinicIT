# ClinicIT Product Specification

## Problem

Small clinics need a simple operational workflow connecting reception, doctors, queues, and patients without forcing staff to coordinate through paper registers, calls, or verbal handoffs.

## Primary users

### Receptionist
- Register and find patients
- Create and confirm appointments
- Record arrival
- Generate and manage queue tokens
- Reschedule/cancel appointments
- View the live queue

### Doctor
- View today's appointments
- See the next patient
- Call the next patient
- Start and complete a consultation
- Record operational consultation notes in later phases

### Patient
- Receive an appointment/queue reference
- See current queue position
- Receive status notifications
- Access a lightweight status page without requiring a full app account in the MVP

### Clinic Admin
- Configure clinic, doctors, schedules, and staff
- View operational analytics
- Manage clinic settings

## MVP workflow

1. Receptionist registers or finds a patient.
2. Receptionist creates an appointment for a doctor and date/time.
3. Appointment is confirmed.
4. Patient arrives and receptionist marks the appointment ARRIVED.
5. System creates/activates a queue entry and assigns a token.
6. Patient becomes WAITING.
7. Doctor calls the next eligible patient.
8. Patient becomes CALLED and is notified.
9. Doctor starts consultation: IN_CONSULTATION.
10. Doctor completes consultation: COMPLETED.
11. Queue advances atomically to the next patient.

## Status model

Appointment status:
- BOOKED
- CONFIRMED
- ARRIVED
- WAITING
- CALLED
- IN_CONSULTATION
- COMPLETED
- CANCELLED
- NO_SHOW
- SKIPPED

## Important domain rules

- Appointment and queue position are separate concepts.
- A patient can have many historical appointments.
- A queue token is unique within a clinic queue/day, not globally.
- Only eligible appointments can enter the active queue.
- Queue transitions must be validated server-side.
- Calling the next patient must be concurrency-safe so two receptionists/doctors cannot claim the same patient.
- A cancelled appointment cannot later become WAITING without an explicit rescheduling/rebooking operation.
- Patient-facing views expose only the minimum information required for queue tracking.
- AI must remain advisory and operational; it must not diagnose, prescribe, or make autonomous clinical decisions.

## Future operational intelligence

- Wait-time prediction
- No-show risk prediction
- Queue/load analytics
- Schedule optimization suggestions
