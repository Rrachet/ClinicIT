# Frontend (Phase 5)

A Next.js app in `frontend/` with role-specific experiences. It talks directly to the Spring Boot REST API
and the STOMP WebSocket; it has no server-side logic of its own.

| Screen | Route | Who | Live updates |
|---|---|---|---|
| Today's Clinic (reception console) | `/reception` | RECEPTIONIST, ADMIN | clinic WebSocket topic |
| My Queue (doctor console) | `/doctor` | DOCTOR | own doctor topic |
| Patient queue status | `/status/{code}` | anyone holding the link, no login | polls a public endpoint every 15 s |
| Clinic analytics | `/admin` | ADMIN | refreshes every minute ([ANALYTICS.md](ANALYTICS.md)) |
| Sign in | `/login` | — | — |

## Structure

```
frontend/src/
  api/        client.ts       the one HTTP client: bearer token, JSON, ApiError, 401 → sign out
              clinicApi.ts    typed wrapper per backend endpoint (no logic)
              types.ts        DTOs mirroring the backend
  auth/       sessionStore.ts session in sessionStorage, read via useSyncExternalStore
              AuthProvider    login / logout / endSession, owns the API client
              routing.ts      which role lands where (navigation only)
              RequireRole     guard for /reception and /doctor
  realtime/   queueFeed.ts    the one STOMP client: read-only, token in CONNECT, reconnects
  queue/      queueStore.ts   PURE queue state + the realtime rules (no React)
              useLiveQueue.ts wires REST boards + WebSocket events into queueStore
              waitEstimate.ts, useWaitEstimates.ts  estimated waits: wording, and a debounced
                              refetch only when a doctor's queue materially changes
  reception/  ReceptionConsole, NowServing, AppointmentTable, NewAppointmentPanel,
              actions.ts (buttons per status), workflows.ts (book / walk-in)
  doctor/     DoctorConsole
  patient/    PatientStatus, statusMessage.ts
  admin/      AnalyticsDashboard, analyticsView.ts (pure formatting)
  ui/         Button, ConfirmDialog, Feedback (error/empty/notice), StatusBadge, AppHeader, format, useAsync
  app/        thin route files only
```

React components render state and call functions; they contain no queue rules. The rules live in plain
TypeScript that is tested without React: `queueStore.ts`, `actions.ts`, `workflows.ts` and `statusMessage.ts`.

## Authentication and authorization

**Signing in.** `POST /api/v1/auth/login` returns the backend's opaque token. It is stored in `sessionStorage`,
not a cookie. The API client adds `Authorization: Bearer …` to every request, and the STOMP client adds the same
header to its CONNECT frame.

**The session ends when:**
- the user signs out, which calls `POST /auth/logout`, so the token is revoked on the server;
- any authenticated request returns 401;
- the WebSocket is refused with "Authentication required";
- the token's `expiresAt` passes. The user is sent to `/login?reason=expired`.

**The frontend does not authorize anything.** Role routing only chooses a screen: ADMIN and RECEPTIONIST go to
`/reception`, DOCTOR to `/doctor`, and anyone else is redirected. Only ADMIN may open `/admin` (analytics). Every request is still authorized by the
backend, including roles, clinic scoping and the doctor-owns-queue rule. The screens simply show its 403/404
answers. The clinic is never chosen by the client: topic paths use `clinicId` from the signed-in user, and the
server re-checks it.

**Why `sessionStorage` rather than an httpOnly cookie.** The backend uses bearer tokens by design (Phase 3), so a
cookie would need a backend-for-frontend or a CSRF story. `sessionStorage` is scoped to one tab, cleared when the
tab closes, and never sent automatically. The remaining risk is XSS. It is reduced by React's escaping, no
`dangerouslySetInnerHTML`, and a CSP `connect-src` that lets the page talk only to itself and the ClinicIT API.

## The real-time client

`queueStore.ts` implements the backend's contract (docs/REALTIME.md):

1. **Deduplicate** on `eventId`, keeping a bounded list of recently seen ids.
2. **Ignore stale events:** an event whose `entryVersion` is less than or equal to the version held for that entry
   is dropped. REST board rows carry the same `version`, so a slow board reload can never overwrite a newer event,
   and an old event can never overwrite a newer board.
3. **Reload after (re)connect:** every CONNECT triggers a board reload, because the server does not replay events.

It also handles two things the contract implies:
- **An event for an unknown entry** (a new join) triggers a reload of that doctor's board, because events carry no
  patient names.
- **An event for another day** is ignored, and a board for a new day starts a fresh queue.

Reception subscribes to `/topic/clinic/{clinicId}/queue`. A doctor subscribes to
`/topic/clinic/{clinicId}/doctor/{ownDoctorId}/queue`. Live events also trigger a debounced refresh of the
appointment list, so appointment statuses stay in step.

## Patient status (no patient accounts)

When a patient joins the queue they are **messaged the link automatically** (Phase 6,
[NOTIFICATIONS.md](NOTIFICATIONS.md)). Reception sees a short notice, and each queued patient's **Messages** button
shows what was sent, with its status and a Retry for failed messages. The link itself (`/status/{code}`) remains
available to copy as a fallback.

- **What the page shows:** token, the token now being served, patients ahead, status, and a clear "You're next"
  or "It's your turn".
- **How it updates:** it polls the public endpoint every 15 seconds while the page is visible. It **never** uses
  the staff WebSocket.
- **What it can't do:** see names, see other patients, or act on the queue. The code stops working after the
  queue day.
- **Wait time:** an estimated range ("17–31 min") while waiting, with a note that it is an estimate, not an
  appointment time (Phase 8, [AI.md](AI.md)).

## API gaps closed for Phase 5 (smallest backend changes)

| Gap | Why the UI needed it | Change |
|---|---|---|
| No way to know the clinic's "today" or name | A browser in another timezone must still show the clinic's day | `GET /api/v1/clinic` → `{id, name, timezone, today}` |
| Appointment lists had no patient name | Otherwise one request per row | `patientName` added to appointment responses |
| REST board rows had no version | Needed to reconcile REST reloads with live events (rule 2) | `version` on board rows and queue-entry responses |
| No patient-facing status at all | Required screen | Unguessable `statusCode` per queue entry + public `GET /api/v1/public/queue-status/{code}` |

All four changes are additive; no existing field or behaviour changed. Everything else maps onto the existing
endpoints.

**Known gaps, not built:**
- **Search by phone.** The backend only searches by name. A phone search is a small, useful future change.
- **Admin screens.** Beyond analytics (`/admin`, Phase 7), an admin uses the reception console; creating users and
  doctors is API-only for now.

## UX decisions

- **Desk speed:**
  - The token is the biggest thing on every screen.
  - Reception keeps one screen: doctor cards with the "Now serving" token and a Call button for each doctor, the
    day's appointments with the next action as the primary button, and a booking panel that is always visible.
  - For a patient at the desk, "Patient is here now" books, confirms, marks arrived and queues in one submit.
    These are four normal API calls, each validated by the server.
- **Which buttons appear:** they follow the backend's state machine (`reception/actions.ts`) as hints only. If
  the server disagrees, its message is shown.
- **Confirmation:** cancel, both kinds of no-show, and the doctor's "Patient not here" (skip) all ask first, and
  focus starts on the safe choice.
- **States:** loading, empty and error states on every screen; errors include a Retry where it helps. A
  Live / Reconnecting indicator is always visible to staff.
- **Accessibility:** labelled form controls; `role=alert` for errors and `status` for notices; buttons with
  accessible names that include the patient; visible focus; `prefers-reduced-motion` respected.
- **Motion and layout:** no animations except the loading spinner. Desktop-first consoles collapse to one column
  under 1080 px; the patient page is designed for phones.

## Running

```bash
cd frontend
cp .env.example .env.local          # NEXT_PUBLIC_API_BASE_URL=http://localhost:8080
npm install
npm run dev                          # http://localhost:3000
```

The backend must allow the frontend's origin: `CLINICIT_CORS_ALLOWED_ORIGINS=http://localhost:3000`. The same
list also governs the WebSocket handshake. `NEXT_PUBLIC_API_BASE_URL` is inlined at build time, so set it before
`npm run build`.

## Tests

```bash
npm test            # Vitest + Testing Library (no backend needed)
npm run typecheck && npm run lint
```

| Suite | Covers |
|---|---|
| `queue/queueStore.test.ts` | Board load; duplicate events; stale versions; board-versus-event reconciliation; unknown entries; other days; per-doctor grouping |
| `queue/useLiveQueue.test.tsx` | Session token on the socket; duplicate and stale events at hook level; **board reload after reconnect**; board fetch for unknown entries |
| `realtime/queueFeed.test.ts` | Token in CONNECT; one read-only subscription; reconnect reporting; stops (no retry loop) on auth error or refused subscription |
| `api/client.test.ts` | Bearer header; anonymous requests; `ApiError` mapping; 401 ends the session (a failed login doesn't); network and non-JSON errors; 204 |
| `auth/*.test.ts(x)` | Login and role routing; wrong credentials; expired session; guard redirects for signed-out users and the wrong role |
| `reception/ReceptionConsole.test.tsx` | Queue rendering; own clinic topic only; live updates; call-next; backend refusal messages; confirm dialogs; 403; retryable load failure |
| `doctor/DoctorConsole.test.tsx` | Own topic and board only; live call; start/complete; 403; socket auth error signs out |
| `admin/*.test.ts(x)` | Analytics formatting (no data is "—"); KPIs, doctor and hourly tables as returned; day and doctor filters; retry; admins only |
| `queue/waitEstimate.test.ts` | Wait-estimate wording ("~23 min", "17–31 min", never "~0 min") and when the queue signature changes |
| `patient/PatientStatus.test.tsx` | Wording; "You're next"; no Authorization header; invalid-link state |

Breaking any of the three realtime rules makes these tests fail. I checked that by removing each one in turn.

### End-to-end (real stack, no mocks)

```bash
# needs PostgreSQL; creates the first admin on an empty database
DB_URL=jdbc:postgresql://localhost:5432/clinicit_e2e DB_USERNAME=postgres DB_PASSWORD=secret scripts/e2e.sh
```

`scripts/e2e.sh` builds and starts the API and the production frontend, seeds staff through the admin API, runs
Playwright, and stops everything. It refuses to start if a port is already taken, so a stale server can never be
tested by mistake.

| Spec | Flow |
|---|---|
| `vertical-slice` | Receptionist and doctor sign in → register patient → book → confirm → arrive → join → call next → doctor's screen updates live (no reload) → start → complete → reception sees it live |
| `workflows` | Walk-in check-in, with the patient following on a phone (anonymous, polling, no staff calls) through "You're next" and "It's your turn" · skip, back in queue, no-show and cancel, with confirmations · no-show for a confirmed patient who never came · a doctor sees only their own queue · role gating, wrong password, and sign-out revoking the token on the server |
| `analytics` | A walk-in booked and called at reception, then completed, shows up in the admin's dashboard for that doctor · receptionists have no Analytics link and are redirected from `/admin` |
| `wait-estimates` | The real ML service answers with its model; reception and the patient see estimates that update as the queue moves |
| `visual-review` | Opt-in (`CAPTURE_SCREENSHOTS=1`): screenshots of all screens for review |
