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

1. **URL level: deny by default.** Only `POST /api/v1/auth/login` and `GET /api/v1/health` are public. Every
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
| `POST /auth/logout`, `GET /auth/me` | ✓ | ✓ | ✓ |
| `POST /users`, `GET /users`, `POST /users/{id}/disable` | ✓ | | |
| `POST /doctors` | ✓ | | |
| `GET /doctors` | ✓ | ✓ | ✓ |
| `POST /patients`, `GET /patients?name=` | ✓ | ✓ | |
| `GET /patients/{id}` | ✓ | ✓ | ✓ |
| `POST /appointments`, `…/confirm`, `…/cancel`, `…/arrive`, `…/no-show` | ✓ | ✓ | |
| `GET /appointments/{id}`, `GET /appointments?date=` | ✓ | ✓ | own only |
| `POST /queue-entries` (join), `…/requeue`, `…/no-show` | ✓ | ✓ | |
| `GET /queue-entries/{id}`, `…/start`, `…/complete`, `…/skip` | ✓ | ✓ | own only |
| `POST /queues/call-next`, `GET /queues/today` | ✓ | ✓ | own only (`doctorId` optional) |

Doctors can read any patient in their clinic by id. That is the one clinic-wide read a doctor has. Narrowing it to
"patients with an appointment with me" is a possible later tightening.

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

Each protection was checked by breaking it on purpose and confirming a test fails: unscoped patient lookup,
unscoped queue-entry lock, a missing role annotation, `permitAll` instead of `authenticated`, and a missing doctor
ownership check.

## Known gaps / next steps

- **No rate limiting or lockout on login yet.** bcrypt makes each guess slow, but online guessing is not
  throttled. Add per-account and per-IP throttling, ideally in Redis, before a public deployment (Phase 9
  hardening).
- **Expired and revoked sessions are never deleted.** Needs a scheduled cleanup job.
- **No password change or reset.** Admins set the initial password. A self-service change needs the current
  password; reset needs email or SMS, which comes with notifications in Phase 6.
- **No audit log** of who changed what. Planned alongside Phase 7.
- **Scoping is enforced in application code plus foreign keys.** PostgreSQL row-level security with a per-request
  `app.clinic_id` setting would add a database-enforced read barrier. It is worth considering once the schema
  settles.
- **CORS** will be configured with the frontend (Phase 5).
