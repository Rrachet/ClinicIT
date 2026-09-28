# Demo Clinic

A ready-to-explore clinic for evaluating ClinicIT: **ClinicIT Demo Clinic, Hyderabad**. It has staff logins, doctors
with schedules, patients, two weeks of history, and today's queue with patients in every state.

## Start it

```bash
docker compose up --build        # then open http://localhost:3000
```

Compose sets `CLINICIT_DEMO_ENABLED=true`. Without Docker, start the API with `CLINICIT_DEMO_ENABLED=true` and
`CLINICIT_DEMO_PASSWORD=<12+ characters>` against an **empty** database.

## Logins

Every demo login uses the same password: `local-demo-only-2026` under Compose (set `CLINICIT_DEMO_PASSWORD` in a
`.env` file to change it before the first start). The code has no default password, so the demo cannot load
without one being set.

| Role | Email | Lands on |
|---|---|---|
| Admin | `admin@demo.clinicit.local` | reception, plus Analytics, Schedules and Team |
| Receptionist | `reception@demo.clinicit.local` | reception |
| Doctor (General Medicine) | `ananya.reddy@demo.clinicit.local` | My Queue |
| Doctor (Paediatrics) | `farhan.siddiqui@demo.clinicit.local` | My Queue |
| Doctor (Dermatology) | `kavya.iyer@demo.clinicit.local` | My Queue |

These are **demo credentials for a local stack**, published on purpose. They grant nothing anywhere else.

## What is loaded

- **Clinic:** Asia/Kolkata time zone.
- **Doctors:** Dr. Ananya Reddy (10-minute appointments), Dr. Farhan Siddiqui (15) and Dr. Kavya Iyer (20). All
  work Monday to Saturday, 09:00–18:00, with lunch 13:00–14:00.
- **Patients:** 18 **fictional** people. Their phone numbers are all in one fake block, `+91 90000 00001`
  to `…00018`, and any resemblance to real people is accidental.
- **History:** the last 14 clinic days (Sundays are closed), plus this morning up to half an hour before start.
  - Each doctor sees 6–10 patients a day, in check-in order, with consultations around their appointment length.
  - About 7% of appointments are cancelled and 8% are no-shows; a few patients are walk-ins.
  - This history feeds the trends, doctor workload, utilization and the wait estimates' history.
- **Today's queue**, created through the same services reception uses:

| Doctor | Patients |
|---|---|
| Dr. Reddy | one with the doctor, two waiting, one who missed their call (skipped) |
| Dr. Siddiqui | one called, two waiting |
| Dr. Iyer | one waiting, one arrived at the desk but not yet queued |
| — | confirmed and booked appointments later today, one cancellation, one no-show |

Today's figures are relative to when the demo was loaded. If you start it late at night, "this morning" has few or
no finished visits, and the live queue is still there.

## How it is loaded

- **When:** only when `clinicit.demo.enabled=true` **and** the database has no clinic yet. It never touches an
  existing clinic, so a restart does nothing.
- **All or nothing:** everything loads in one transaction.
- **Today's live state** goes through the real appointment and queue services, so tokens, status links, history
  events, the real-time outbox and (recorded, never sent) notifications are exactly what reception would produce.
- **Past visits** are written directly with the timestamps they would have had, because the services always record
  "now". They obey every constraint live data does: foreign keys, one token per clinic per day, the append-only
  history, and one active patient per doctor. The history is written in time order, so its sequence numbers are
  chronological.
- **Schedules** are set last, so they apply to new bookings, not to the history.

`DemoDataIntegrationTest` checks all of this:
- the logins work, and the patients and schedules are there;
- today has patients in every state, and the boards and wait estimates work on them;
- 14 clinic days of history with no Sundays, in time order, with gap-free tokens;
- trends and workload are populated;
- it loads only into an empty database, and refuses to start without a password.

## Never in production

The `prod` profile refuses to start when `CLINICIT_DEMO_ENABLED` is set ([OPERATIONS.md](OPERATIONS.md)), and
`ProductionConfigurationCheckTest` covers that.
