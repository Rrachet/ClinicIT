# Operational Analytics (Phase 7)

ClinicIT keeps a permanent history of every appointment and queue transition. It computes the clinic's operational
figures from that history with plain SQL in PostgreSQL. There is no machine learning, no estimation and no LLM:
every number is a count, a time between two recorded events, or a ratio of counts. The same data always gives the
same answer.

This history is also the foundation for later phases, such as no-show prediction and wait-time estimates. Those
need facts that current state cannot provide. For example, requeueing a patient clears the entry's `called_at`, but
the history still has their first call.

## Architecture

```
AppointmentService ─┐  (inside the transition's own transaction)
QueueService ───────┴─► LifecycleHistory ──INSERT──► operational_events   (append-only; trigger rejects UPDATE/DELETE)
                                                              │
                        AnalyticsController ─► AnalyticsService ─► SQL aggregates (clinic-scoped, clinic-local days)
                              (GET only)                        │
                                                   /admin dashboard (ADMIN)
```

- **Still a modular monolith.** There are two new modules:
  - `history`: the write side. It exposes no endpoints.
  - `analytics`: read-only endpoints. It queries the history table and does not load entities.

  There is no Kafka, warehouse or second database. The one PostgreSQL database, with three indexes, serves these
  queries easily at clinic scale.
- **Why a new table instead of `queue_events`.** `queue_events` (Phase 4) is a short-lived outbox for WebSocket
  delivery. It is purged after 7 days, and it covers only queue changes. `operational_events` is the permanent
  record of the whole lifecycle, from booking to completion.
- **Why direct calls instead of domain events and a listener.** Notifications are best-effort, so their listener
  swallows failures. History must be complete: if it silently missed an event, every figure built on it would be
  wrong. So the services call `LifecycleHistory` directly with `Propagation.MANDATORY`. If an event can't be
  written, the transition fails with it.

## The event history

Table `operational_events` (migration `V10`). There is one row per lifecycle transition.

| Column | |
|---|---|
| `id` | event id (UUID) |
| `seq` | insertion order; breaks ties between events with the same timestamp |
| `clinic_id` | always set; composite foreign keys force the appointment, doctor and patient to belong to it |
| `appointment_id` | always set |
| `queue_entry_id` | set for queue events (WAITING onwards) |
| `doctor_id`, `patient_id` | ids only. The patient id is pseudonymous and kept for later per-patient no-show history |
| `event_type` | see below |
| `previous_status` | appointment status before the transition (null only for BOOKED) |
| `occurred_at` | the transition's own timestamp. It is the same `Instant` written to the entity (e.g. `called_at`) |
| `actor_user_id` | the staff user who caused it. It is deliberately not a foreign key, so history outlives accounts |
| `token_number` | queue token, for queue events |
| `scheduled_at` | clinic-local appointment time, recorded on BOOKED |

**Metadata is minimal and structured on purpose.** There are no names, phone numbers, reasons for visit or free
text. The table holds no personal data beyond ids.

### Event types

| Event | Recorded when | Previous status |
|---|---|---|
| `BOOKED` | appointment created | — |
| `CONFIRMED` | confirmed | BOOKED |
| `ARRIVED` | marked arrived | CONFIRMED |
| `WAITING` | **joined the queue** (exactly once per queue entry) | ARRIVED |
| `CALLED` | called (each time) | WAITING |
| `IN_CONSULTATION` | consultation started | CALLED |
| `COMPLETED` | consultation completed | IN_CONSULTATION |
| `CANCELLED` | cancelled | BOOKED or CONFIRMED |
| `SKIPPED` | skipped | WAITING or CALLED |
| `REQUEUED` | a skipped patient came back (the appointment goes back to WAITING) | SKIPPED |
| `NO_SHOW` | no-show | CONFIRMED (never came), ARRIVED (left before queueing) or SKIPPED (left the queue) |

Returning to the queue is `REQUEUED`, not a second `WAITING`, so `WAITING` always means "joined". Each event's
`previous_status` is the status the prior event left behind (treating REQUEUED as WAITING). The stress test checks
that this chain is unbroken.

### Guarantees

- **Transactional.** Events are inserted in the transaction of the transition. A rolled-back transition leaves no
  event, and an event that can't be written rolls back the transition. `LifecycleHistory` flushes JPA first, so the
  foreign keys see the new appointment or queue entry.
- **Immutable.** A `BEFORE UPDATE OR DELETE` row trigger raises `append-only` for any change, whoever issues it:
  the application, a script or an admin console. The `history` module only inserts and exposes no endpoints, and
  every analytics endpoint is `GET`. A test asserts both. (TRUNCATE is a separate statement and privilege, used by
  the test suite. In production, grant the application role only INSERT and SELECT on the table.)
- **Ordered.** Per appointment, events are serialized by the appointment row lock that every transition already
  takes, so `seq` order is the real order for each appointment.
- **Timestamps are preserved, not reconstructed.** Arrival, queue join, every call, consultation start,
  completion, skip, requeue, no-show and cancellation times all come from the history. None is read from the
  current row, which forgets things: `called_at` is cleared on requeue and `skipped_at` is overwritten.

**No backfill.** Events start when V10 is deployed. Appointments from before then have no history, and analytics
for those days are incomplete. They are never reconstructed from current state, which would invent timestamps.

## Definitions

All figures are for one **clinic-local calendar day**. `2026-03-10` in an Asia/Kolkata clinic is
`2026-03-09T18:30Z` to `2026-03-10T18:30Z`, whatever the server's or browser's timezone. Hours are clinic-local
hours: `occurred_at at time zone <clinic timezone>`. Durations are in seconds. A figure with no data (nobody called
yet, nothing scheduled) is `null`, never `0`, so "no data" is never mistaken for "no wait".

A **visit** is one queue entry that joined the queue (`WAITING`) that day.

| Figure | Definition |
|---|---|
| Patients | visits that day (patients checked into the queue) |
| Scheduled appointments | appointments whose `scheduled_at` is on that day (walk-ins included) |
| Completed consultations | visits with a `COMPLETED` event |
| **Wait** (per visit) | first `CALLED` − `WAITING`. A requeued patient's wait ends at their *first* call; the time spent away after a skip is not "waiting for the doctor" |
| Average / median wait | `avg` / `percentile_cont(0.5)` over visits that have been called. The median is interpolated for an even count; the p90 uses `percentile_cont(0.9)` |
| Consultation duration | `COMPLETED` − `IN_CONSULTATION` |
| Appointment delay | first `CALLED` − `scheduled_at` (converted from clinic time). Positive is late |
| Cancellation rate | cancelled ÷ scheduled |
| No-show rate | no-shows ÷ (scheduled − cancelled): of the patients still expected, the share who did not come. It includes all three kinds of no-show (never arrived, left before queueing, left the queue) |
| Throughput | `COMPLETED` events per clinic-local hour |
| Queue length over time | running sum of +1 for `WAITING`/`REQUEUED` and −1 for any event whose `previous_status` is `WAITING` (called, or skipped while waiting), taken at the end of each hour. Hours run from 0 to the current hour today, to 23 for past days, and none for future days |
| Current queue length | that running sum over the whole day: patients waiting now (for a past day, left waiting at day end) |
| Doctor utilization | total consultation time ÷ (last completion − first call) for that doctor that day, capped at 1. **This is a proxy.** Working hours aren't modelled yet, so it measures how busy the doctor was while seeing patients, not against their schedule |

**Edge cases:**
- A patient skipped and never called again is a no-show ("left the queue"). They count in Patients but have no
  wait.
- A patient called, skipped, requeued and called again is counted once, with the wait to their first call.
- Yesterday's patient left in WAITING is not in today's queue length, because the queue is per day.

## API

All endpoints are `GET`, require any staff role, and use the clinic from the session. There is no `clinicId`
parameter; one sent anyway is ignored. `date` is a clinic-local ISO date and defaults to the clinic's today.

| Endpoint | Returns |
|---|---|
| `GET /api/v1/analytics/today?date=&doctorId=` | the day's summary: patients, scheduled, completed, average/median wait, average consultation, average delay, cancellations and no-shows with rates, current queue length |
| `GET /api/v1/analytics/wait-times?date=&doctorId=` | called patients, average/median/p90/max wait, and the same per hour of call (the trend) |
| `GET /api/v1/analytics/doctors?date=` | every doctor of the clinic: patients called and handled, average wait, average and total consultation time, utilization |
| `GET /api/v1/analytics/queue?date=&doctorId=` | current queue length; per hour: joined, completed (throughput), waiting at the end of the hour |
| `GET /api/v1/analytics/no-shows?from=&to=&doctorId=` | for appointments scheduled in the range (default: the last 30 days, at most 366): scheduled, cancelled, no-shows, rates, the three kinds of no-show, per day |

**Who sees what:**

| Role | Access |
|---|---|
| ADMIN, RECEPTIONIST | the whole clinic, or one doctor via `doctorId`. A doctor id from another clinic returns 404 |
| DOCTOR | always and only their own figures. `doctorId` defaults to themselves, another doctor's id returns 403, and `/doctors` returns only their own row |
| Anyone else | 401 (checked by `EndpointSecurityCoverageTest`) |

Invalid dates or ranges return 400.

**Example**, `GET /api/v1/analytics/today`:

```json
{
  "date": "2026-03-10", "timezone": "Asia/Kolkata", "doctorId": null,
  "scheduledAppointments": 8, "patients": 5, "completedConsultations": 4,
  "averageWaitSeconds": 2325.0, "medianWaitSeconds": 2100.0,
  "averageConsultationSeconds": 975.0, "averageDelaySeconds": 2325.0,
  "cancellations": 1, "noShows": 1, "cancellationRate": 0.125, "noShowRate": 0.14285714285714285,
  "currentQueueLength": 1
}
```

## Admin dashboard

The dashboard is at `/admin`, for the ADMIN role only. Admins still land on the reception console, and an
**Analytics** link appears in the header.

- **Today:** patients (with the number booked), completed, average and median wait, no-show rate (with the counts
  behind it), and average consultation. Below them: cancellations and the average delay against appointment time.
- **Doctors:** a table with patients seen, average wait, average consultation, and utilization (bar and
  percentage). A note explains what utilization means.
- **Queue:** the number waiting now, then one row per hour from the first busy hour. Each row shows checked in,
  completed (throughput), waiting at the end of the hour, and the average wait of patients called that hour (the
  trend). Every bar has its number next to it.
- **Controls:** day and doctor filters, and Refresh. The dashboard refreshes every minute while showing today.
- **No charting library.** The bars are plain CSS and styled like the rest of ClinicIT. The frontend does no
  arithmetic on the data beyond formatting; `admin/analyticsView.ts` is pure and unit-tested.

## Tests

All tests run against real PostgreSQL; the JVM runs in America/Los_Angeles while the clinic is in Asia/Kolkata.

| Suite | Proves |
|---|---|
| `LifecycleHistoryIntegrationTest` | Every transition records the right event, time, actor, previous status, entry and token, through a full visit, skip/requeue/no-show, cancellation and both pre-queue no-shows. The first call time survives the requeue that clears it on the entry. **A rolled-back operation leaves no event.** **UPDATE and DELETE are rejected.** No endpoint writes history, and analytics is GET-only |
| `AnalyticsIntegrationTest` | A scripted day checked against hand-computed figures: summary, **median (even and odd counts)**, p90, hourly wait trend, per-doctor figures and utilization, throughput and queue length by hour. **Skip, requeue and no-show are each counted correctly.** **An empty day gives zeros and nulls.** **Days and hours use clinic-local time, not UTC or the server's zone.** No-show ranges and validation. **Doctor scoping (403), front-desk narrowing, and no access for another clinic, including over HTTP with a forged `clinicId`** |
| `QueueStressIntegrationTest` | Under 12 concurrent workers, the history stays consistent: one event per committed transition (event count = entry version + 1), one WAITING per entry, an unbroken status chain in time order, the latest event matching every appointment's status, and the queue length replayed from history equal to the live count |
| Frontend `analyticsView.test.ts`, `AnalyticsDashboard.test.tsx` | Formatting (no data shows "—", not "0 min"); hourly merge; KPIs, doctor table and queue table as returned by the API; day and doctor filters sent to the API; error with retry; receptionists redirected away |
| Browser `analytics.spec.ts` | A walk-in booked and called in the reception UI, then completed, appears in the admin's dashboard filtered to that doctor. Receptionists have no Analytics link and are redirected from `/admin` |

These tests fail if their safeguard is removed. I checked that by removing each in turn:
- a UTC day window instead of the clinic's (1 failure);
- the last call instead of the first (3);
- history written in its own transaction (5);
- no doctor scoping (2);
- the mean reported as the median (2).

## Not in this phase

- **Machine learning or predictions**, such as expected wait or no-show risk. The history they need now exists.
- **Doctor schedules and working hours**, which would make utilization exact.
- **Backfill** of pre-Phase-7 appointments, deliberately.
- **Materialized rollups.** Indexed queries over one clinic-day are fast. Pre-aggregation can come later if
  multi-year range queries need it.
