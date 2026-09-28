# Operations and Deployment

How to run ClinicIT in production, what it needs, and how to see whether it is healthy. It describes the current
system, not a target architecture.

## What runs

| Process | Needs | Scale |
|---|---|---|
| Spring Boot API (`target/clinicit-*.jar`, Java 21) | PostgreSQL 16 | **one instance** (see "WebSocket scaling boundary") |
| Next.js frontend (`frontend/`, `npm run build && npm start`) | the API's public URL at build time | stateless; any number |
| Wait-time ML service (`ml/`, FastAPI; optional) | its model file | stateless; any number |
| PostgreSQL 16 | disk, backups | managed service recommended |

There is no Redis, message broker or second database.

## Containers

`Dockerfile` (API), `ml/Dockerfile` and `frontend/Dockerfile` build the three images; each runs as a non-root user.
`compose.yaml` runs all of them with PostgreSQL on one machine for evaluation (see the README). It is **not** a
production setup: it runs without the `prod` profile and binds every port to 127.0.0.1. CI builds the images and
smoke-tests the compose stack on every pull request (`scripts/ci/compose-smoke.sh`).

## Configuration

Start the API with `SPRING_PROFILES_ACTIVE=prod`. Every setting is an environment variable; `.env.example` lists them
all with placeholders. **Never commit real values.**

The `prod` profile has **no defaults** for anything environment-specific. `ProductionConfigurationCheck` runs before
any bean is created (so before migrations) and refuses to start, listing every problem at once, when:

- `DB_URL`, `DB_USERNAME` or `DB_PASSWORD` is missing, or the password is a known development default (`clinicit`,
  `postgres`, `password`, …);
- `CLINICIT_CORS_ALLOWED_ORIGINS` is missing, contains a wildcard or a non-`https` origin;
- `CLINICIT_PUBLIC_APP_URL` (used in patients' status links) is missing or not `https`;
- `CLINICIT_NOTIFICATIONS_PROVIDER` is missing, or notifications are enabled with the **development** provider, which
  records messages instead of sending them;
- `spring.jpa.hibernate.ddl-auto` is anything but `validate`/`none` (schema changes go through Flyway only);
- `CLINICIT_ML_BASE_URL` is set but is not an `http(s)` URL.

| Variable | Default (local development only) | |
|---|---|---|
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | `localhost:5432/clinicit`, `clinicit`, `clinicit` | required in prod |
| `DB_POOL_SIZE` | `10` | Hikari pool; a request waits at most 5 s for a connection |
| `CLINICIT_CORS_ALLOWED_ORIGINS` | *(none: no cross-origin access)* | exact origins, comma-separated |
| `CLINICIT_PUBLIC_APP_URL` | `http://localhost:3000` | frontend URL for status links |
| `CLINICIT_NOTIFICATIONS_ENABLED` / `_PROVIDER` / `_CHANNEL` | `true` / `development` / `SMS` | see Notifications below |
| `CLINICIT_ML_BASE_URL`, `CLINICIT_ML_TIMEOUT` | *(empty: ML off)*, `PT0.8S` | |
| `CLINICIT_SESSION_TTL` | `PT12H` | staff login lifetime |
| `PORT`, `MANAGEMENT_PORT` | `8080`, `8081` | public API; internal probes and metrics |
| `CLINICIT_BOOTSTRAP_*` | *(none)* | first clinic and admin on an empty database |
| `clinicit.public-status.*` | `PT1M`, 300 requests, 20 unknown codes | per-address limit on the patient status page ([SECURITY.md](SECURITY.md#public-patient-status)) |

**Development defaults are safe by construction.** Without the prod profile the app talks to a local database, allows
no cross-origin calls, never sends a real message (the development provider has no network access), and has the ML
service off.

### Notifications in production

No real SMS/WhatsApp/email provider ships yet, so production runs with `CLINICIT_NOTIFICATIONS_ENABLED=false` and
`CLINICIT_NOTIFICATIONS_PROVIDER=none`: no messages are created, and patients get their status link from reception
(the link is shown in the reception console). Adding a provider is described in [NOTIFICATIONS.md](NOTIFICATIONS.md).
The development provider is refused in prod because patients would silently receive nothing.

### ML service

Optional. Without `CLINICIT_ML_BASE_URL` every estimate comes from the deterministic baseline. With it, the API calls
the service with a 0.8 s budget and falls back to the baseline on any failure ([AI.md](AI.md)). Train the model
(`python -m clinicit_ml.train`) and run `uvicorn clinicit_ml.api:create_default_app --factory` with
`CLINICIT_MODEL_DIR`. Keep it on an internal network; it has no authentication of its own and receives no patient
data, only queue features.

## Database

- **Migrations:** Flyway runs at startup (`V1`–`V11`). Hibernate only validates the schema.
- **Credentials:** the application's database user needs DML on the application tables. `operational_events` is
  append-only by trigger; for defence in depth, grant the application user only `INSERT, SELECT` on it.
- **Errors:** `logServerErrorDetail=false` keeps PostgreSQL's row contents out of exception messages and logs.
- **Indexes:** reviewed against real query plans; see [DATA_MODEL.md](DATA_MODEL.md#indexes).
- **Backups and point-in-time recovery** are the database platform's job and are not configured by ClinicIT.
- **Growth:** notifications are deleted 30 days after they finish, the real-time outbox after 7 days, expired login
  sessions hourly. `operational_events` is permanent by design (it is the history).

## Health checks

Actuator runs on the **internal** management port (`MANAGEMENT_PORT`, default 8081). Expose it to the orchestrator
and the metrics scraper only, never to the internet. Only `health`, `info` and `prometheus` are exposed; any other
actuator path is refused.

| Probe | URL | UP when | Use |
|---|---|---|---|
| Liveness | `GET :8081/actuator/health/liveness` | the process is running | restart the container if DOWN |
| Readiness | `GET :8081/actuator/health/readiness` | it can reach PostgreSQL | stop routing traffic if DOWN (503) |
| Public ping | `GET :8080/api/v1/health` | the web server answers | simple external uptime checks |

**Liveness never depends on PostgreSQL or the ML service.** If the database goes away, readiness turns DOWN, traffic
stops, and the instance recovers by itself when the database returns, instead of being restarted in a loop. The ML
service is in neither probe, because estimates fall back to the baseline without it. A test starts the application
with an unreachable database and checks exactly this (`ProbesWithoutDatabaseTest`).

## Metrics

Prometheus format at `GET :8081/actuator/prometheus`. No metric carries a clinic, doctor or patient identifier;
per-clinic figures are the analytics module's job.

| Metric | Type | Meaning |
|---|---|---|
| `http_server_requests_seconds{uri,method,status,outcome}` | histogram | every API request; URIs are templates (`/api/v1/queue-entries/{id}`), so ids and status codes never become labels |
| `hikaricp_connections_{active,idle,pending}`, `hikaricp_connections_timeout_total` | gauge / counter | database pool health |
| `clinicit_appointments_total{status}` | counter | appointments reaching each status: `BOOKED` is creation, `COMPLETED` completion, `CANCELLED` and `NO_SHOW` losses |
| `clinicit_public_status_rejected_total` | counter | patient status requests refused by the per-address rate limit |
| `clinicit_queue_joins_total`, `clinicit_queue_calls_total` | counter | committed check-ins and calls |
| `clinicit_queue_transitions_total{to}` | counter | IN_CONSULTATION, COMPLETED, SKIPPED, WAITING (requeue), NO_SHOW |
| `clinicit_queue_wait_seconds` | histogram | check-in to first call |
| `clinicit_consultation_duration_seconds` | histogram | consultation start to completion |
| `clinicit_notifications_deliveries_total{outcome,channel,reason}` | counter | `sent`, `retry`, `failed` (reason: EXPIRED, ATTEMPTS_EXHAUSTED, PROVIDER_ERROR, …) |
| `clinicit_notifications_pending` | gauge | the unsent backlog |
| `clinicit_websocket_connections{state=open\|authenticated}` | gauge | live staff screens on this instance |
| `clinicit_ml_requests_seconds{outcome}` | histogram | ML calls: success, timeout, unreachable, http_error, bad_response, invalid_prediction |
| `clinicit_ml_backoff_skips_total` | counter | calls not made during the 30 s failure back-off |
| `clinicit_wait_estimates_total{source,reason}` | counter | estimates by MODEL/BASELINE; the BASELINE share is the fallback rate |
| `clinicit_wait_estimates_cache_total{result}` | counter | hit / miss |

Appointment and queue metrics are recorded **after commit**, so rolled-back operations are never counted (a test checks this).

Suggested alerts: readiness DOWN; `hikaricp_connections_pending > 0` for minutes; 5xx rate; `clinicit_notifications_pending`
growing while `deliveries{outcome="sent"}` is flat; ML fallback rate high after a model deploy.

## Logging

- **Format:** plain text locally; **one JSON object per line** (Elastic Common Schema) in prod.
- **Request id:** every request gets an `X-Request-Id` (a proxy's id is reused only if it is a short safe token). It is
  on every log line of the request (`requestId`) and in the response header, so a user's error report can be matched
  to the logs.
- **Never logged:** passwords, bearer or session tokens, token hashes, patient names, phone numbers, reasons for
  visit, message bodies, status-link codes (masked as `***` in error logs), ML request bodies. PostgreSQL error
  details that would contain row contents are switched off. `LogSafetyIntegrationTest` runs a full flow and checks
  the captured log for each of these.

## WebSocket scaling boundary

ClinicIT is deployed as **one API instance**. The real-time queue uses Spring's in-memory STOMP broker, which delivers
only to browsers connected to the same instance. Running two instances behind a load balancer today would mean:

1. **Missed live updates:** a change committed on instance A reaches only A's screens; B's screens update only on
   their next reload.
2. **Slower socket revocation on other instances:** a logout closes the user's sockets on the instance that handled
   it immediately; on others, the minute-by-minute revalidation closes them (it re-checks tokens in the database).
3. **Per-instance caches and limits:** wait-estimate caching, the ML back-off and the patient status page's rate
   limit are per instance (N instances allow N times the rate; less strict, not wrong).

Everything else is already multi-instance safe: tokens, locks, sessions, login throttling, the outboxes and
notification claiming all live in PostgreSQL, and the scheduled cleanups are idempotent.

**To run several instances**, replace the in-memory broker with a shared fan-out: Spring's STOMP broker relay to
RabbitMQ or ActiveMQ (a configuration change in `WebSocketConfig`), or PostgreSQL `LISTEN/NOTIFY` from the outbox so
every instance publishes every event to its own clients. Also broadcast `SessionsRevoked` the same way. The event
contract and the clients do not change. This is deliberately not built until one instance is not enough.

## Security checklist for a deployment

- `SPRING_PROFILES_ACTIVE=prod`, secrets from a secret store, TLS terminated in front of the API and frontend.
- The management port reachable only from the orchestrator and Prometheus.
- The ML service on an internal network only.
- The API behind a proxy that sets `X-Forwarded-For` (trusted only from internal addresses; login throttling uses
  the client IP).
- More: [SECURITY.md](SECURITY.md).
