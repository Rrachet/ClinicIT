# Doctor Scheduling

Phase 10. Each doctor can have weekly working hours with a lunch break, an appointment length, and leave. Booking
follows them. The design is deliberately small: the week repeats, and nothing else does.

## What is modelled

| Concept | Stored as | Example |
|---|---|---|
| Working days and hours | one row per working day: ISO day of week, start, end (`doctor_working_hours`) | Monday 09:00–17:00 |
| Break | an optional break start and end on that row, inside the hours | 13:00–14:00 |
| Appointment length | `doctor_profiles.appointment_minutes`, 5–120 | 15 |
| Leave and unavailable periods | a clinic-local date-time range with an optional staff note (`doctor_time_off`) | 2026-03-16 00:00 → 2026-03-17 00:00, "Conference" |
| Days off | a day with no row | Sunday |

**Not modelled, on purpose:** fortnightly or monthly rotas, multiple shifts per day, per-date exceptions to the hours
(use time off), per-appointment durations, rooms or resources, recurring leave. Each would complicate every rule below
for a need no clinic in scope has yet.

**No hours set means no rules.** A doctor without any working hours can be booked at any time, exactly as before
Phase 10. Clinics adopt schedules one doctor at a time, and nothing breaks for those that never do.

## Time model

Appointments, hours and leave are clinic **wall-clock** time. What happened is recorded as **instants**.

| Type | Used for | Why |
|---|---|---|
| `LocalDateTime` (`timestamp without time zone`) | `appointments.scheduled_at`, `doctor_time_off.starts_at/ends_at` | "10:30 on the 16th" means 10:30 on the clinic's clock, whatever the server's zone |
| `LocalTime` + ISO `DayOfWeek` | weekly hours and breaks | "Mondays 09:00" is the same wall-clock time every week |
| `Instant` (`timestamptz`) | history events, sessions, notifications, `created_at` | a point in time, compared across clinics and servers |
| `ZonedDateTime` | only while converting: the clinic's `timezone` (e.g. `Asia/Kolkata`) turns "now" into the clinic's date and time (`ClinicTime`) | the one place a zone is applied |

The backend never uses the server's zone. The test JVM runs in `America/Los_Angeles` against `Asia/Kolkata`
clinics, so an accidental use of the server's zone fails the tests. India has no daylight saving time. In a zone that
does, a wall-clock time can be skipped or repeated once a year; schedules are wall-clock by design, so "09:00" stays
09:00 across the change.

Ranges are **end-exclusive**: a 10:00–10:15 slot and a 10:15–10:30 slot do not overlap. A whole day of leave is
00:00 to 00:00 the next day.

## Booking rules

These apply only to doctors who have hours.

**A booked appointment** (a time chosen by reception) must:

| Rule | Refused with (`409`, `code`) |
|---|---|
| be on a working day | `DAY_OFF` |
| fit inside the hours: start at or after the start and **end** (start + length) by the end | `OUTSIDE_HOURS` |
| not overlap the break | `ON_BREAK` |
| not overlap time off | `TIME_OFF` |
| not start in the past | `IN_THE_PAST` |
| not overlap another booked appointment of the doctor | `SLOT_TAKEN` |

Appointments that are cancelled or no-show free their slot; every other status holds it. Times are stored to the
minute, and need not be on the slot grid: the grid is what availability offers, and a slot is taken if any of it
overlaps.

**A walk-in** (`walkIn: true`: the patient is at the desk now) is booked for the current minute and joins the queue
in arrival order. Walk-ins **never hold a slot**: a fully booked day cannot turn one away, and one cannot take a
booked patient's time. A walk-in is refused only when the doctor won't work again today: `DAY_OFF`, or
`NOT_WORKING_TODAY` (after the end of the hours, or on leave until then). Arriving early, during the break or
while the doctor is briefly away is fine; the patient waits.

**Rescheduling** (`POST /appointments/{id}/reschedule`) moves a booked or confirmed appointment to another time with
the same doctor under the same rules (it does not collide with itself). Arrived, queued and finished appointments
and walk-ins are not moved. The history records a `RESCHEDULED` event with the new time; the status is unchanged.

**Leave over existing bookings** is allowed. Nothing is cancelled automatically: the response says how many booked
or confirmed appointments fall inside it, and reception moves or cancels them with each patient.

## Concurrency

Two receptionists could both see the same free slot and book it. Booking and rescheduling take the **doctor row
lock** (`FOR NO KEY UPDATE`) before checking for overlaps, so the check and the insert are serialised per doctor.
Other doctors are not blocked. The doctor row comes first, the same lock order as the queue engine
([QUEUE_ENGINE.md](QUEUE_ENGINE.md)): doctor → queue entry → appointment → token counter. Rescheduling reads the
appointment's doctor without a lock, locks the doctor, then locks the appointment. Changing a doctor's week takes
the same lock, so no booking is checked against a half-replaced week.

`twoReceptionistsCannotBothTakeTheLastSlot` books one slot from four threads at once: exactly one succeeds. With
the lock removed, the test fails (checked by mutation). Walk-ins take no lock, because they have no slot to race for.

## API

| Endpoint | Roles | |
|---|---|---|
| `GET /api/v1/doctors/{id}/schedule` | staff | weekly hours, appointment length, recent and upcoming time off |
| `PUT /api/v1/doctors/{id}/schedule` | admin | replaces the week: `{appointmentMinutes, weeklyHours: [{dayOfWeek, start, end, breakStart?, breakEnd?}]}`; `[]` removes the schedule |
| `POST /api/v1/doctors/{id}/time-off` | admin | `{startsAt, endsAt, reason?}` → `{timeOff, bookedAppointments}` |
| `DELETE /api/v1/doctors/{id}/time-off/{timeOffId}` | admin | |
| `GET /api/v1/doctors/{id}/availability?date=` | staff | the day's slots, each `available` or with a `reason`; `scheduled: false` when the doctor has no hours |
| `POST /api/v1/appointments` | front desk | now also `walkIn: true` (then `scheduledAt` is not needed) |
| `POST /api/v1/appointments/{id}/reschedule` | front desk | `{scheduledAt}` |

Invalid weeks are refused with `400 INVALID_SCHEDULE` (end before start, a break outside the hours or with only one
end, a day listed twice, a length outside 5–120). Every endpoint is scoped to the caller's clinic, and the database
rejects a working-hours or time-off row whose doctor belongs to another clinic (composite foreign keys).

## Screens

- **Reception, new appointment:** "Patient is here now" books a walk-in. Otherwise the receptionist picks a date and
  one of the doctor's **free slots**. For a doctor without a schedule, a free time input as before.
- **Reception, appointment list:** *Reschedule* on booked and confirmed appointments (not walk-ins) opens the same
  slot picker.
- **Admin → Schedules:** per doctor, the week (works / from / to / break) and appointment length, and leave: add whole
  days with a note for staff, see what is planned, remove it.

The browser only offers choices; the server checks every rule again, and a slot taken meanwhile is refused with a
clear message.

## Analytics and wait estimates

- **Utilization against the schedule.** `GET /analytics/doctors` adds `scheduledMinutes` (hours minus break and
  leave that day) and `scheduledUtilization` (time in consultation ÷ scheduled minutes). The older `utilization`
  (busy time ÷ first call to last completion) stays: it measures how busy the doctor was while seeing patients,
  the new figure how much of their scheduled time went into consultations. Both are null without the data they need.
- **Wait estimates know about breaks.** The ML model and the baseline assume the doctor keeps working. When a break,
  leave, or the start of the day falls within a patient's estimated wait, those minutes are added (to each bound of
  the range separately). This is a deterministic adjustment on top of the model, not part of it: the model and its
  evaluation ([AI.md](AI.md)) are unchanged. Nothing is added past the end of the day's hours.

## Tests

| Test | What it proves |
|---|---|
| `DoctorScheduleTest` | every rule on its own: fit, break edges, time off edges, the past, day off, slot grid resuming after the break, walk-in cases, invalid hours, working minutes, pauses ahead (overlapping break and leave counted once) |
| `SchedulingIntegrationTest` | over HTTP: roles, validation, no-schedule compatibility, each refusal code, freed slots, leave reporting and removal, walk-ins during the break and after closing, rescheduling and its history event, availability reasons, **concurrent booking of one slot**, clinic isolation (API and database), scheduled utilization, break-aware wait estimates |
| `NewAppointmentPanel.test`, `ReceptionConsole.test`, `ScheduleEditor.test`, `scheduleForm.test` | the screens send exactly what the API expects, offer only free slots, and show the server's reasons |
