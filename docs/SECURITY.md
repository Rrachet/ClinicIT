# Authentication, Authorization and Clinic Isolation (Phase 3)

ClinicIT holds patient data for several independent clinics in one database. The goal of this phase is that a
staff member can only ever see and change their own clinic's data, and within it only what their role needs.
Both are enforced on the server, and the tests try to break them.

## Model

```
Clinic 1 ── N UserAccount (email, bcrypt password hash, role, enabled)
                  │
                  └── DOCTOR accounts link to exactly one DoctorProfile of the same clinic
UserAccount 1 ── N AuthSession (SHA-256 of the token, expires_at, revoked_at)
```

| Role | Can do |
|---|---|
| `ADMIN` | Everything the front desk can, plus staff accounts (`/users`) and doctor profiles (`POST /doctors`) |
| `RECEPTIONIST` | Patients, appointments and every doctor's queue in the clinic |
| `DOCTOR` | Own appointments and own queue only: view them, call next, start, complete, skip |

The admin also has front-desk rights because in a small clinic the owner often covers the desk. A user belongs
to exactly one clinic. Emails are unique across all clinics because login is by email alone.

## Authentication

- **Login:** `POST /api/v1/auth/login {email, password}` returns
  `{accessToken, tokenType: "Bearer", expiresAt, user}`. Every other request sends
  `Authorization: Bearer <accessToken>`.
- **Logout:** `POST /api/v1/auth/logout` revokes the token used for that request. `GET /api/v1/auth/me` returns the
  current user.

**Passwords.** Stored with Spring Security's `DelegatingPasswordEncoder`, which today means bcrypt stored as
`{bcrypt}$2a$…`. The `{id}` prefix lets the algorithm be upgraded later without a flag day. Passwords must be
12–72 characters; bcrypt ignores anything after 72 bytes. They are never logged: the request DTOs mask them in
`toString`.

**Login failures are indistinguishable.** An unknown email, a wrong password and a disabled account all return
`401 INVALID_CREDENTIALS` with the same message. For an unknown email the server still checks the password
against a dummy bcrypt hash, so response time does not reveal which emails have accounts.

**Tokens are opaque and revocable, not JWTs.** A token is 256 bits from `SecureRandom`, base64url-encoded, and
the database stores only its SHA-256.

| | Opaque session token (chosen) | Self-contained JWT |
|---|---|---|
| Logout, disabling a user, a stolen device | Takes effect on the next request | Valid until it expires, unless a denylist is added (which is a database lookup again) |
| Database leak | Hashes are useless as tokens | Nothing stored, but the signing key is a single point of failure |
| Cost | One indexed lookup per request | None |
| Needed for | A single-region modular monolith | Many independent services |

For a healthcare system, "disable this account now" matters more than saving one indexed query. SHA-256 is enough
for the stored token (bcrypt is not needed) because the token has full entropy, so it cannot be brute-forced.

**Session lifetime.** Absolute, 12 hours by default, which covers one clinic shift. Set it with
`CLINICIT_SESSION_TTL` (ISO-8601, e.g. `PT8H`). Logout revokes one token. Disabling a user revokes all of theirs.

**Implementation.** Spring Security's standard OAuth2 resource-server bearer-token filter does the header parsing,
the 401 and the `WWW-Authenticate` challenge. ClinicIT plugs in its own `OpaqueTokenIntrospector`
(`SessionTokenIntrospector`), which resolves the token to an `Actor(userId, clinicId, role, doctorProfileId)`.
There is no external authorization server; the resource-server module is used only for its filter.

**Stateless and no cookies.** There are no server-side HTTP sessions and nothing is sent in cookies, so CSRF
protection does not apply and is disabled. If the frontend later stores the token in a cookie, CSRF must be
re-enabled.

**Brute-force protection.** Counters live in PostgreSQL (`login_throttle`), so there is no extra infrastructure,
and several app instances share them. The keys are SHA-256 hashes, so typed emails and IP addresses are not stored
in clear.

| Limit | Default | Counts | Reset |
|---|---|---|---|
| Per account (email) | 5 per 15 min | every attempt, **reserved before** the password check with an atomic upsert, so parallel guesses cannot all get past | on successful login, or when the window ends |
| Per client IP | 30 per 15 min | failures only, so one address cannot spray many accounts, while a clinic's staff behind one NAT can still log in | when the window ends |

A throttled login returns exactly the same `401 INVALID_CREDENTIALS` as a wrong password, does the same bcrypt
work, and sends no `Retry-After` header. The response therefore reveals neither that an account exists nor that it
is locked. Unknown emails are throttled too.

The throttle writes in its own transaction (`REQUIRES_NEW`). A failed login rolls back its own transaction, and
the failure count must survive that rollback; a test catches the difference.

The client IP is the server-observed remote address. Behind a reverse proxy, set
`server.forward-headers-strategy=native` (or `framework`) so it is the real client address, not the proxy's.

The trade-off: someone who knows a user's email can keep that account locked by repeatedly failing its login. This
is the usual cost of per-account lockout. It is limited to 15-minute windows, and other accounts are unaffected.

**Password change.** `POST /api/v1/auth/password {currentPassword, newPassword}` returns `204`.
- It requires the current password. Wrong guesses count against the same per-account limit, so a stolen token
  cannot be used to brute-force the password.
- On success, **every session of the user is revoked, including the one used for the request**. Other devices and
  any stolen token stop working at once, and the client must log in again.
- Errors: `400 INVALID_CURRENT_PASSWORD`, `400 PASSWORD_UNCHANGED`, and `400 VALIDATION_ERROR` for a password under
  12 characters.

**Cleanup.** `AuthCleanup` runs hourly (`clinicit.auth.cleanup-interval`). It deletes sessions that are expired or
revoked, since they can never authenticate again, and throttle rows whose window has passed. It deletes in batches
of 1000, each in its own short transaction, so a backlog never holds long locks. The deletes are idempotent, so
running on several instances at once is safe. Scheduling can be switched off with `clinicit.scheduling.enabled=false`.

**CORS.** `CLINICIT_CORS_ALLOWED_ORIGINS` is a comma-separated list of exact origins, such as
`https://app.clinicit.example`.
- Wildcards and non-origin values (a path or query) are refused at startup. An empty list, the default, allows no
  cross-origin access.
- Allowed: methods `GET`, `POST`, `PUT`, `DELETE` (the last two for doctor schedules); request headers `Authorization`, `Content-Type`; exposed `WWW-Authenticate`;
  preflight cached for 1 hour.
- `allowCredentials` is **false**. Tokens travel in the `Authorization` header, never in cookies, so the browser
  has no ambient credential to send.
- CORS headers are also added to 401/403 responses, so the frontend can read the error.

**First admin.** On an empty database, `BootstrapAdmin` creates the first clinic and its admin from environment
variables. Nothing is committed to Git. It does nothing once any user exists.

```bash
CLINICIT_BOOTSTRAP_CLINIC_NAME="City Clinic" \
CLINICIT_BOOTSTRAP_ADMIN_EMAIL=owner@cityclinic.example \
CLINICIT_BOOTSTRAP_ADMIN_PASSWORD='<at least 12 characters>' \
mvn spring-boot:run
```

After that, the admin creates doctor profiles (`POST /api/v1/doctors`) and staff accounts (`POST /api/v1/users`;
a DOCTOR account needs `doctorProfileId`).

## Authorization: three layers

1. **URL level: deny by default.** Only `POST /api/v1/auth/login`, `GET /api/v1/health` and the patient status
   endpoint `GET /api/v1/public/queue-status/{code}` are public. Every
   other request, including unknown routes, needs a valid token, otherwise `401 UNAUTHORIZED`.
2. **Role level.** Every endpoint declares who may call it, with `@AdminOnly`, `@FrontDesk` (ADMIN, RECEPTIONIST)
   or `@AnyStaff`. These are meta-annotations over `@PreAuthorize`. A call with the wrong role gets
   `403 FORBIDDEN`.
3. **Data level: clinic scoping and doctor ownership, in the application services.**
   - Every service method takes the `Actor` explicitly. Every lookup by id is clinic-scoped in the query itself,
     for example `findByIdAndClinicId(id, actor.clinicId())` or `findByIdAndClinicIdForUpdate`. The unscoped
     lock queries were removed so they cannot be used by mistake.
   - Creation always uses `actor.clinicId()`. `clinicId` was removed from request bodies and parameters; a client
     that still sends one has it ignored, and a test proves this.
   - A doctor acting on another doctor's appointment or queue entry gets `403`.
     `actor.resolveDoctor(doctorId)` also lets doctors omit `doctorId` to mean themselves.

**Why 404, not 403, for another clinic's data.** A resource in another clinic returns the same `404 NOT_FOUND` as
an id that does not exist, so a caller cannot even confirm that it exists. `403` is used only where the caller is
allowed to know the resource exists: the wrong role, or a colleague's queue in the same clinic.

**Why pass `Actor` explicitly.** The alternative is reading the security context inside services. Passing it
makes every service signature show that the operation is clinic-scoped. It also keeps services free of Spring
Security, and lets tests call services as any role without mocking security.

### The database as the last line of defence

Composite foreign keys (V4, V5) make cross-clinic references impossible, even from a buggy code path or a manual
SQL fix:

- an appointment's patient and doctor are in its clinic
- a queue entry has exactly its appointment's clinic and doctor
- a DOCTOR account's profile is in the account's clinic

### Endpoint matrix

| Endpoint | ADMIN | RECEPTIONIST | DOCTOR |
|---|---|---|---|
| `POST /auth/login` | public | public | public |
| `POST /auth/logout`, `GET /auth/me`, `POST /auth/password` | ✓ | ✓ | ✓ |
| `POST /users`, `GET /users`, `POST /users/{id}/disable` | ✓ | | |
| `POST /doctors` | ✓ | | |
| `GET /doctors` | ✓ | ✓ | ✓ |
| `POST /patients`, `GET /patients?name=` | ✓ | ✓ | |
| `GET /patients/{id}` | ✓ | ✓ | ✓ |
| `POST /appointments`, `…/confirm`, `…/cancel`, `…/arrive`, `…/no-show`, `…/reschedule` | ✓ | ✓ | |
| `GET /doctors/{id}/schedule`, `GET /doctors/{id}/availability` | ✓ | ✓ | ✓ |
| `PUT /doctors/{id}/schedule`, `POST /doctors/{id}/time-off`, `DELETE /doctors/{id}/time-off/{id}` | ✓ | | |
| `GET /no-show-risk?date=` (advisory) | ✓ | ✓ | |
| `GET /no-show-risk/evaluation` | ✓ | | |
| `GET /appointments/{id}`, `GET /appointments?date=` | ✓ | ✓ | own only |
| `POST /queue-entries` (join), `…/requeue`, `…/no-show` | ✓ | ✓ | |
| `GET /queue-entries/{id}`, `…/start`, `…/complete`, `…/skip` | ✓ | ✓ | own only |
| `POST /queues/call-next`, `GET /queues/today` | ✓ | ✓ | own only (`doctorId` optional) |
| `GET /clinic` | ✓ | ✓ | ✓ |
| `PUT /clinic` (name only) | ✓ | | |
| `GET /notifications?appointmentId=`, `POST /notifications/{id}/retry` | ✓ | ✓ | |
| `GET /analytics/*` | ✓ | ✓ | own figures only |
| `GET /queues/today/wait-estimates` | ✓ | ✓ | own queue only |
| `GET /public/queue-status/{code}` | public | public | public |

Doctors can read any patient in their clinic by id. That is the one clinic-wide read a doctor has. Narrowing it to
"patients with an appointment with me" is a possible later tightening.

### Public patient status

`GET /api/v1/public/queue-status/{code}` is the one public data endpoint.

- **The code:** 128 random bits per queue entry, handed to that patient by reception. It is not guessable, and it
  is only a read capability.
- **What it returns:** token, current token, patients ahead, status, the clinic's and doctor's display names, and
  (Phase 8) an estimated wait range while waiting. It returns no patient name, phone number, appointment details,
  internal ids or model details. A test pins the exact set of fields.
- **Expiry and caching:** unknown codes and codes from past queue days both return 404. Responses are
  `Cache-Control: no-store`.
- **Rate limit:** per client address, at most 300 requests and 20 unknown codes a minute
  (`clinicit.public-status.*`). A waiting room sharing one Wi-Fi address stays well inside the first; the second
  stops code guessing before it costs database queries. Past either limit every status request from that address
  gets `429` with `Retry-After` until the minute ends; the patient page keeps the last status and retries. The
  counters are in memory, per instance, and hold addresses only for the current minute; addresses are never
  logged. Refusals are counted in `clinicit_public_status_rejected_total`.
- **Read-only:** the endpoint has only `GET`; nothing a patient does changes the queue.
- **Referrers:** the frontend sends `Referrer-Policy: no-referrer`, so the code is never leaked in a Referer header.
- **Tests:** they check that the code grants nothing else, for example it doesn't work as a bearer token.
- **How patients get the code:** from Phase 6 it is sent to the patient's phone in the "checked in" message
  (docs/NOTIFICATIONS.md). Message bodies use fixed templates with no clinical data, phone numbers are masked for
  staff and never logged, and message rows are purged after 30 days.

## Error responses

| Status | Code | When |
|---|---|---|
| 401 | `UNAUTHORIZED` | no token, or an unknown, expired or revoked token (`WWW-Authenticate: Bearer …` header) |
| 401 | `INVALID_CREDENTIALS` | login failed, for any reason |
| 403 | `FORBIDDEN` | wrong role, or a doctor acting on a colleague's data |
| 404 | `NOT_FOUND` | does not exist, **or belongs to another clinic** |
| 409 | `EMAIL_TAKEN`, `DOCTOR_ALREADY_LINKED`, `CANNOT_DISABLE_SELF` | user management rules |

All use the same `ApiError` body. `@PreAuthorize` denials happen inside the controller call, so they reach the
`@RestControllerAdvice` before Spring Security's filters. They are mapped there explicitly; otherwise the
catch-all handler would turn them into 500s.

## Tests

| Suite | What it proves |
|---|---|
| `ClinicIsolationIntegrationTest` | Clinic A's admin, receptionist and doctor attack 20 Clinic B operations (reads, every state change, cross-clinic references in bodies, user management). The admin gets exactly 404 every time. No response contains Clinic B data, listings and search never include it, a smuggled `clinicId` is ignored, and nothing in Clinic B changes. |
| `EndpointSecurityCoverageTest` | Walks **every** mapped `/api` endpoint: each non-public one returns 401 without a token and declares its roles. New endpoints are covered automatically. |
| `RoleAuthorizationIntegrationTest` | Doctors cannot do front-desk or admin work. Receptionists cannot manage staff. Doctors run their own queue without naming themselves and get 403 on a colleague's. |
| `AuthenticationIntegrationTest` | Login, case-insensitive email, identical failures, bcrypt storage, token hashing, expiry, logout revoking only that token, the 401 challenge. |
| `UserManagementIntegrationTest` | Account creation rules, disabling revokes tokens immediately, the admin cannot lock themselves out, the database rejects cross-clinic doctor links. |
| `BootstrapAdminIntegrationTest`, `ActorTest` | First-admin bootstrap, doctor/front-desk resolution rules. |
| `LoginThrottleIntegrationTest` | Account lock after 5 attempts with an identical generic 401; temporary lock; success resets the counter; unknown emails throttled; per-IP spray blocked without penalising NAT'd staff; 20 parallel guesses all counted; no clear-text keys. |
| `PasswordChangeIntegrationTest` | Every session revoked; old password dead, new one works; wrong current password changes nothing; a stolen token cannot brute-force the current password; length and "unchanged" rules. |
| `AuthCleanupIntegrationTest` | Expired and revoked sessions purged, active ones kept; stale throttle rows purged. |
| `CorsIntegrationTest` | Allowed-origin preflight without credentials; unknown origin rejected; CORS headers on 401; wildcard configuration refused. |

Each protection was checked by breaking it on purpose and confirming a test fails: unscoped patient lookup,
unscoped queue-entry lock, a missing role annotation, `permitAll` instead of `authenticated`, and a missing doctor
ownership check.

## Production hardening (Phase 9)

- **Configuration cannot silently be unsafe.** The `prod` profile has no defaults for credentials, CORS or the public
  URL, and `ProductionConfigurationCheck` refuses to start with missing values, development passwords, wildcard or
  non-`https` CORS origins, or the development notification provider ([OPERATIONS.md](OPERATIONS.md)).
- **Clinic scoping all the way down.** The last id-only lookups (patient names for lists and boards, the public
  status's doctor) now use clinic-scoped queries, and the notification planner's joins match on `clinic_id` too.
  `ClinicIsolationIntegrationTest` covers every resource type (patients, appointments, queue entries, doctors,
  users, notifications, analytics, wait estimates) and proves the database rejects cross-clinic references even from
  code that bypasses the services.
- **Actuator is internal.** Probes and metrics are on a separate management port, only `health`, `info` and
  `prometheus` are exposed, and every other actuator path answers 401. No metric carries clinic, doctor or patient
  identifiers.
- **Logs carry no secrets or patient data.** No password, token, token hash, phone, name, reason, message body or
  status-link code is logged. PostgreSQL error details (which contain row contents) are disabled, and the status code
  is masked in error logs. A test runs a full flow and checks the captured output.
- **Request ids.** Each request is tagged with `X-Request-Id`; a client-supplied id is kept only if it is a short safe
  token, so it cannot inject text into the logs.
- **Client IPs behind a proxy.** The `prod` profile honours `X-Forwarded-For` only from internal proxy addresses, so
  a client cannot spoof its IP to evade per-IP login throttling.

## Known gaps / next steps

- **No password reset** ("forgot password"). The notification channel exists (Phase 6), but only a development
  provider is configured and staff have no verified phone or email. For now an admin creates a new account.
- **Partial audit trail.** Every appointment and queue transition records the acting user in the immutable
  `operational_events` history (Phase 7). Other changes are not audited: staff accounts, doctor profiles and
  patient details.
- **Scoping is enforced in application code plus foreign keys.** PostgreSQL row-level security with a per-request
  `app.clinic_id` setting would add a database-enforced read barrier. It is worth considering once the schema
  settles.
- **Throttling is per account and per IP only.** A distributed attack from many IPs against many accounts is slowed
  by bcrypt but not blocked. A WAF or CAPTCHA at the edge covers that tier.
