# Queue Engine (Phase 2)

The queue engine turns an **arrived appointment** into a **numbered place in line** and moves it through
the consultation. The key distinction is that an appointment is not a place in the queue:

```
Appointment 16:00, Dr. Sharma  ──arrive──▶  ARRIVED  ──join──▶  Token #27 (WAITING)  ──▶ CALLED ──▶ IN_CONSULTATION ──▶ COMPLETED
```

## Problems found in the Phase 0/1 model

| Problem | Impact | Fix |
|---|---|---|
| `clinics.created_at` mapped by the entity but never created by V2 | App fails to start on Postgres (`ddl-auto: validate`); hidden because tests ran on H2 with Flyway disabled | V3 adds the column; queue tests run against real Postgres with Flyway and `validate` |
| `queue_entries` had no `doctor_id` | "Call next for Dr. Sharma" was not answerable | V3 adds `doctor_id` (copied from the appointment at join, immutable) |
| `findLatestForUpdate` locked existing rows to compute `max(token)+1` | Race on the first token of the day: there are no rows to lock, so two joins both take #1 | Per-clinic/day counter row with an atomic upsert (below) |
| `cancel` allowed from any non-completed status | A patient mid-consultation could be cancelled, leaving the queue entry dangling | Status changes go through an explicit transition table; cancel only from BOOKED/CONFIRMED |
| Status had a public setter | Any code could bypass the state machine | Setter removed; `Appointment.transitionTo` / `QueueEntry` methods are the only way |
| Not-found raised `IllegalArgumentException` | Returned HTTP 500 | `NotFoundException` → 404 |
| Appointment creation did not check the patient/doctor belong to the clinic | Cross-clinic data leak into queues | Checked on create (404 if not in clinic) |

## State machines

Appointment (the queue drives everything from WAITING onwards):

```
BOOKED → CONFIRMED → ARRIVED → WAITING → CALLED → IN_CONSULTATION → COMPLETED
BOOKED, CONFIRMED → CANCELLED
ARRIVED           → NO_SHOW
WAITING, CALLED   → SKIPPED
SKIPPED           → WAITING   (requeue)
SKIPPED           → NO_SHOW
```

Queue entry: the same states from WAITING onwards. Each queue status maps 1:1 onto the appointment status of
the same name, and every queue mutation updates both **in one transaction**. A unit test asserts that every
queue transition is also a legal appointment transition, so the two machines cannot drift apart.

### Additions to the planned model (and why)

The planned model was kept. It was extended in three places:

- **CALLED → SKIPPED.** The plan had WAITING → SKIPPED. In practice the usual skip is "called, didn't come
  in", so both are allowed.
- **SKIPPED → WAITING (requeue).** A patient who stepped out and came back. They keep their original token,
  which is lower than everyone behind them, so they are called next. This matches how front desks usually
  handle a returning patient.
- **SKIPPED → NO_SHOW.** End-of-day cleanup for a skipped patient who never returned.

**Open question:** there is no transition from CONFIRMED to NO_SHOW for a patient who booked and never arrived.
That is the most common no-show in practice, and the future no-show prediction will need it recorded. It was
not added because it changes the agreed model.

## Invariants and where they are enforced

| Invariant | Service | Database |
|---|---|---|
| Token unique per clinic per day | Counter upsert | `uk_queue_clinic_date_token` |
| One queue entry per appointment | Appointment row lock + ARRIVED check | `unique (appointment_id)` |
| At most one active (CALLED / IN_CONSULTATION) patient per doctor per day | Doctor row lock + check | partial unique index `uk_queue_one_active_per_doctor` |
| Valid status values | enums + transition tables | `check` constraints |
| Token > 0 | counter starts at 1 | `check` constraint |

The database constraints are tested directly with SQL (`QueueSchemaConstraintsIntegrationTest`), so they hold
even if a future code path bypasses the service.

## Concurrency-safe token generation

`max(token_number) + 1` fails under concurrency: two transactions read the same max. Locking the existing rows
does not help for the first patient of the day, because there is nothing to lock yet.

Instead, `queue_token_counters(clinic_id, queue_date, last_token)` is incremented with one statement:

```sql
insert into queue_token_counters (clinic_id, queue_date, last_token)
values (?, ?, 1)
on conflict (clinic_id, queue_date)
do update set last_token = queue_token_counters.last_token + 1
returning last_token;
```

- **Atomic.** It works for both the first and later tokens of the day, with no read-then-write gap.
- **Serialised.** The row lock is held until the join commits, so concurrent joins at the same clinic wait on
  it. Joins are short, so this costs little.
- **No gaps.** If the join fails later (for example, the appointment is not for today), the increment rolls
  back with it. A Postgres `SEQUENCE` would leave gaps and cannot reset per day, which is why it wasn't used.

Tokens are **per clinic** rather than per doctor, as specified. One waiting room has one number sequence, so a
patient's token never collides with another doctor's patient.

## Locking strategy

All queue mutations run under `READ COMMITTED` (the Postgres default), with explicit row locks:

| Operation | Locks (in order) |
|---|---|
| join | appointment → token counter |
| call next | doctor → next WAITING entry (`FOR UPDATE SKIP LOCKED`) → its appointment |
| start / complete / skip / requeue / no-show | queue entry → its appointment |
| confirm / cancel / arrive / no-show (appointment) | appointment |

Locks are always taken in the order *doctor → entry → appointment → counter*, so no two operations can wait on
each other in a cycle (no deadlocks).

- **Why lock the doctor row for call-next.** "Check that nobody is active, then call the next patient" must be
  atomic. Two receptionists pressing *Call next* together are serialised per doctor. The second one sees the
  first patient already CALLED and gets `DOCTOR_BUSY`. Other doctors are not blocked.
- **Why `SKIP LOCKED`.** A locked WAITING row is being changed right now, for example skipped. With a plain
  `FOR UPDATE ... LIMIT 1`, Postgres waits for that row, re-checks it after the skip commits, finds it no
  longer WAITING, and returns **no row** even though others are waiting. `SKIP LOCKED` moves on to the next
  patient. A test reproduces exactly this.
- **Optimistic `@Version` on `queue_entries`** is defence in depth. A stale in-memory entry can never
  overwrite a newer one. Conflicts map to `409 CONCURRENT_MODIFICATION`.

### Rule: one active patient per doctor

*Call next* is rejected while the doctor has a CALLED or IN_CONSULTATION patient. The receptionist or doctor
completes (or skips) the current patient first. This prevents double calls and keeps "current token" on the
patient screen well defined. It can be relaxed later, for example to allow calling the next patient early,
by allowing two CALLED entries in the partial index.

### "Today"

"Today" is the **clinic's** local date (`clinics.timezone`), not the server's. It is computed from an injected
`Clock`, so tests are deterministic. Only appointments scheduled for today can join the queue.
`appointments.scheduled_at` is interpreted as clinic-local time.

## API

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/v1/queue-entries` `{appointmentId}` | Join the queue; issues the token → `201` |
| GET | `/api/v1/queue-entries/{id}` | One entry |
| POST | `/api/v1/queue-entries/{id}/start` | CALLED → IN_CONSULTATION |
| POST | `/api/v1/queue-entries/{id}/complete` | IN_CONSULTATION → COMPLETED |
| POST | `/api/v1/queue-entries/{id}/skip` | WAITING/CALLED → SKIPPED |
| POST | `/api/v1/queue-entries/{id}/requeue` | SKIPPED → WAITING |
| POST | `/api/v1/queue-entries/{id}/no-show` | SKIPPED → NO_SHOW |
| POST | `/api/v1/queues/call-next` `{doctorId}` | Call the lowest waiting token for the doctor |
| GET | `/api/v1/queues/today?doctorId=` | Staff board: current token, waiting count, entries with patient name and patients ahead |
| POST | `/api/v1/appointments/{id}/no-show` | ARRIVED → NO_SHOW (left before joining the queue) |

Changes from the Phase 0 sketch:

- `POST /queues/{appointmentId}/join` → `POST /queue-entries`. Joining *creates* a queue entry, so it is a
  `POST` to the collection. It also removes an ambiguity: in the sketch, `/queues/{id}` meant an appointment
  id in one route and a queue-entry id in the others.
- `POST /queues/next/call` → `POST /queues/call-next` with `{doctorId}`. The queue is per doctor, so the
  doctor has to be identified. In Phase 3 a doctor's own calls will take it from the authenticated user.
- `requeue` and `no-show` were added to cover the full state machine.

Error contract (`ApiError.code`):

| Status | Code | When |
|---|---|---|
| 404 | `NOT_FOUND` | unknown appointment / entry / doctor |
| 409 | `INVALID_STATE` | transition not allowed from the current status |
| 409 | `NOT_TODAY` | joining with an appointment not scheduled today |
| 409 | `DOCTOR_BUSY` | call-next while the doctor has an active patient |
| 409 | `QUEUE_EMPTY` | call-next with nobody waiting |
| 409 | `CONCURRENT_MODIFICATION` | lock / version conflict; safe to retry |
| 409 | `CONSTRAINT_VIOLATION` | a DB invariant caught something the service did not |
| 400 | `VALIDATION_ERROR` | malformed request |

`patientsAhead` counts WAITING patients with a lower token. Skipped patients do not count.

## Testing

Queue tests run against **real PostgreSQL**, because the design depends on Postgres-specific behaviour: row
locks, `SKIP LOCKED`, `ON CONFLICT` and partial indexes. Flyway runs every migration with
`ddl-auto: validate`, so the tests also prove the schema matches the entities.

- `CLINICIT_TEST_DB_URL=jdbc:postgresql://localhost:5432/clinicit_test mvn test` uses an existing database
  (`CLINICIT_TEST_DB_USERNAME` / `CLINICIT_TEST_DB_PASSWORD` are optional).
- Otherwise Testcontainers starts `postgres:16-alpine` when Docker is available.
- With neither, the Postgres tests are **skipped** (reported as skipped, not passed).

| Suite | Covers |
|---|---|
| `AppointmentStatusTest`, `QueueEntryTest` | Transition tables, timestamps, queue↔appointment consistency |
| `QueueServiceIntegrationTest` | Tokens per clinic/day, clinic timezone, join rules, FIFO, DOCTOR_BUSY, skip/requeue/no-show, board |
| `QueueConcurrencyIntegrationTest` | 24 simultaneous joins → tokens 1..24 exactly; same appointment joined 10× → one entry, counter = 1; 10 simultaneous call-next → exactly one call; SKIP LOCKED behaviour; skip vs start race |
| `QueueSchemaConstraintsIntegrationTest` | DB constraints hold when the service is bypassed |
| `QueueApiIntegrationTest` | Receptionist → doctor flow over HTTP, error codes, cross-clinic appointment rejected |

The concurrency tests were checked by breaking each safeguard in turn and confirming the matching test fails:
the naive `max+1` allocator, dropping `SKIP LOCKED`, and removing the doctor lock.

## Deliberately deferred

- **Authentication and clinic scoping (Phase 3).** Endpoints currently trust the ids they receive. The
  service already derives the clinic from the appointment or doctor, so Phase 3 only has to check that it
  matches the caller's clinic.
- **Real-time events (Phase 4).** Each queue mutation is a single service method, which is the natural place
  to publish `queue.updated` / `patient.called` after commit.
- **Public patient status page.** Needs a non-guessable status token per queue entry and a minimal, PII-free
  response.
- **Estimated wait.** Needs consultation-duration history (`consultation_started_at` → `completed_at` is now
  recorded) before an estimate means anything.
