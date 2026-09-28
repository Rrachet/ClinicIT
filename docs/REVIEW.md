# Final Review

An honest assessment of ClinicIT after Phase 12, in three parts:
- a security review of the whole surface, looking for ways to break it;
- a performance review with measurements;
- the review a senior interviewer would give.

It records what was checked and how, what was found and fixed, and what remains.

## 1. Security review

**Method.** Every endpoint was listed with its roles (the matrix in [SECURITY.md](SECURITY.md)). For each one, I
asked: who can call it, which clinic's data can it reach, what it logs, and what an attacker controls.
`EndpointSecurityCoverageTest` fails the build if any `/api` endpoint lacks a role declaration or answers
without a token.

| Area | Checked | Result |
|---|---|---|
| Authentication | opaque session tokens (stored as SHA-256), expiry, logout, password change and disabling all revoke server-side; WebSockets re-validated every minute and closed on revocation | ✓ tested over REST and WebSocket, including in the browser (`team.spec.ts`: a disabled receptionist's next page load lands on sign-in) |
| Brute force | per-account attempt reservation and per-IP failure counting, in PostgreSQL | ✓ tested |
| Clinic isolation | every lookup by id is scoped to the caller's clinic (`…AndClinicId`); composite foreign keys make the database reject cross-clinic rows, including the Phase 10 schedule tables | ✓ `ClinicIsolationIntegrationTest`, `SchedulingIntegrationTest`, `NoShowRiskIntegrationTest` |
| Roles | scheduling writes, the clinic name and the no-show evaluation are admin-only; doctors cannot see no-show flags or other doctors' figures | ✓ tested (403) |
| Public status page | read-only by an unguessable 128-bit code, no patient name, rate limited per address (300 requests / 20 unknown codes a minute), `no-store`, never logged | ✓ tested, including a mutation check of the guessing limit |
| Injection | all SQL is parameterised; the only formatted SQL fragments are constants (status lists, optional `and doctor_id = ?`) | ✓ code review |
| Secrets | none in git; production refuses development passwords, wildcard or plain-HTTP CORS, demo data and the recording notification provider, listing every problem at once | ✓ `ProductionConfigurationCheckTest`, including a real start |
| Logs | no passwords, tokens, names, phones, reasons, message bodies or status codes | ✓ `LogSafetyIntegrationTest` |
| Browser | CSP limiting where scripts may connect, `no-referrer` (status links carry a code), `nosniff`, framing denied | ✓ configured; the E2E runs under it |
| CORS | exact origins only; Phase 10 added `PUT` and `DELETE`. The new E2E test caught that it was missing, which MockMvc could not | ✓ preflight test |
| AI features | wait estimates and the no-show flag are advisory and read-only; no endpoint reacts to them; neither uses demographics | ✓ tested (a flagged patient is booked and served as usual) |

**Findings and decisions:**
- **Fixed during the phases:** the missing `PUT`/`DELETE` CORS methods (Phase 10); and the lack of any way for staff to
  replace an initial password (Phase 12 added `/account`).
- **Leave notes are visible to all staff.** Time-off reasons are shown to every staff member of the clinic, doctors
  included. The field is labelled "Note for staff". **Do not record medical reasons there.** This is documented;
  restricting the note to admins would be a small follow-up.
- **Doctors can read any patient of their clinic by id** (pre-existing; documented in SECURITY.md as a possible later
  tightening).
- **Accepted:** the demo password is published in `compose.yaml` and the README on purpose. It exists only in the
  demo stack, and production refuses to start with demo data.
- **Not done:** no penetration test by a third party, no dependency-vulnerability scanning in CI beyond `npm ci`'s
  audit summary, no 2FA, no password reset by email.

## 2. Performance review

**Setup.** One API instance, local PostgreSQL 16, and the demo clinic loaded with **300 clinic days** of history:
7,193 appointments, 45,361 history events, 6,087 queue entries. Each figure is the best of 5 requests after a
warm-up, measured with `curl` on the same machine.

| Endpoint | Time |
|---|---|
| Reception: today's appointments | 24 ms |
| Queue board for a doctor | 24 ms |
| Wait estimates for a doctor's queue | 16 ms |
| Doctor availability for a day | 14 ms |
| No-show flags for today | 38–61 ms |
| Analytics: today / doctors | 13 / 18 ms |
| Trends: 14 / 92 days | 16 / 51 ms |
| No-show evaluation: 90 / 365 days | 169 / 342 ms |
| Public patient status page | 5 ms |

**Found and fixed:**
- **No-show evaluation: 1.6 s → 40 ms** on the 14-day demo. The first version ran correlated subqueries per day. It now
  reads each appointment's outcome times once and applies the as-of rules in memory, with identical results (same
  figures before and after; the hand-computed tests and the leakage mutation test still hold).
- **Stale statistics after the demo load: 6.2 s → 61 ms** for today's no-show flags, and 0.96 s → 0.03 s for trends.
  The bulk load left PostgreSQL planning with no statistics until autovacuum caught up. The loader now runs
  `ANALYZE` on the tables it filled. Verified with autovacuum switched off.
- **Earlier phases:** the index review on 60 simulated days (Phase 9, [DATA_MODEL.md](DATA_MODEL.md#indexes)) and
  the wait-estimate cache (one ML call per doctor per queue change).

**Not measured:**
- concurrent load beyond the concurrency tests;
- several clinics in one database at scale;
- a year of real data;
- latency over a real network or a hosted database.

## 3. The senior interviewer's review

**What is strong:**
- **The core is right.** Tokens come from an atomic counter, never `MAX + 1`; locks are taken in one documented order;
  a partial unique index backs "one active patient per doctor"; bookings serialise per doctor. Each claim has a
  concurrency test against real PostgreSQL, and several were checked by mutation, i.e. the test was shown to fail
  when the safeguard was removed.
- **Multi-tenancy is enforced twice,** in the queries and in the schema.
- **History is immutable and time-aware.** Analytics, the wait-time features and the no-show evaluation all read it
  "as of" a moment, and leakage is tested rather than asserted.
- **The AI is honest:**
  - The wait-time model beats its baseline on a simulation, and says that it is a simulation.
  - The no-show flag is a rule, because the data cannot support a model.
  - The evaluation on the demo data shows it adds nothing there, and the product says so.
- **Operability:** safe-by-default configuration, probes, metrics, request ids, structured logs, and a CI pipeline that
  builds and starts the whole Docker stack.
- **The product works end to end:** Playwright drives reception, doctor, patient, admin, scheduling and team flows
  against the real stack in CI.

**What an interviewer would push on:**
1. **Single instance.** The in-memory STOMP broker caps it at one API instance. Documented, with the migration path
   (STOMP relay or PostgreSQL `LISTEN/NOTIFY`), but not built.
2. **No real notifications.** The outbox, retries and idempotency are there; a vendor integration is not, so
   production runs with messages off.
3. **Synthetic data everywhere.** The wait-time model has never seen a real clinic. Its reported accuracy is a
   property of the simulation.
4. **Baseline estimates ignore the patient already with the doctor.** It counts only patients ahead: the next patient
   sees "~1 min" while a consultation is still running. The ML model accounts for it; the baseline rule does not.
   This is documented, and changing it means changing the shared baseline contract with the Python service.
5. **Scheduling is intentionally simple:** weekly hours, one break and leave. No per-date exceptions, no multiple
   shifts, no per-appointment durations.
6. **Deployment is described, not proven.** The Docker images run in CI, but no Vercel/Render/Railway deployment was
   made from this repository.
7. **Frontend tests are mostly component-level with a fake API.** The E2E suite covers the main flows, but not every
   screen state.

**Verdict.** Solid engineering on the parts that are hard to get right: concurrency, isolation, time, leakage and
honesty about AI. The main remaining gaps are integration and scale: a messaging vendor, horizontal scaling, and
real data. They are known and documented.
