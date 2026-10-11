# Deploying ClinicIT

The selected free-first deployment target is:
- **Frontend:** Vercel (`frontend/`, already deployed)
- **API:** Koyeb Web Service built from the repository-root `Dockerfile`
- **Database:** the existing Neon PostgreSQL project/production branch
- **ML:** omitted initially; the deterministic wait-time fallback is built in

Koyeb documents one free Web Service per organization (512 MB RAM, 0.1 vCPU, 2 GB SSD); it sleeps after one hour
without traffic. Koyeb may require a payment method during account validation, so do not proceed with any paid plan or
add a card if that conflicts with your free-first constraint. The free instance is intended for preview/hobby use, not
a production clinic with real patient data. See the [Koyeb pricing FAQ](https://www.koyeb.com/docs/faqs/pricing)
and [instance limits](https://www.koyeb.com/docs/reference/instances).

The repository's root Dockerfile and production configuration are ready for a container-based API deployment. The
full hosted stack is **not yet deployed or verified**; CI's Docker Compose smoke test is a local-stack check, not proof
of a public deployment. The requirements and safeguards are in [OPERATIONS.md](OPERATIONS.md).

## 1. What to deploy

| Piece | Source | Where | Public? |
|---|---|---|---|
| PostgreSQL 16 | existing managed database | Neon production branch | no |
| API | `Dockerfile` (repo root) | Koyeb Web Service, one free instance | yes: HTTPS and WebSockets on `PORT` |
| ML service (optional) | `ml/Dockerfile` | private service, same platform as the API | **no**: it has no authentication |
| Web app | `frontend/` | Vercel | yes |

**Run exactly one API instance.** The real-time queue uses an in-memory broker, so a second instance would miss
live updates ([OPERATIONS.md](OPERATIONS.md#websocket-scaling-boundary)). Do not enable autoscaling.

## 2. Database (existing Neon production branch)

Use the existing Neon project `tiny-bonus-47281441`, branch `production`. Do not create a second database unless the
existing branch is unavailable. Retrieve its connection details from Neon and keep them private.

- `DB_URL` must be a JDBC URL: `jdbc:postgresql://HOST/DB?sslmode=require` (preserve any required Neon TLS
  parameters supported by the driver).
- `DB_USERNAME` and `DB_PASSWORD` are the role credentials for that connection.
- Do not paste the full connection string into a public issue, README or commit.
3. Keep `DB_POOL_SIZE` (default 10) well inside the plan's connection limit.
4. **Backups:** turn on automated backups and point-in-time recovery on the platform. ClinicIT does not do backups.

Flyway creates the schema on the API's first start. Hibernate never changes it.

## 3. API on Koyeb

1. Open the [Koyeb control panel](https://app.koyeb.com/) and create a Web Service from the GitHub repository
   `Rrachet/ClinicIT`, branch `main`. Authorize Koyeb to access this repository if prompted.
2. Select **Dockerfile** as the builder and leave the Dockerfile path at the repository root (`Dockerfile`).
3. Select the `free` instance if available and a supported free region. Expose **8080 / HTTP** at route `/`.
   Do not expose 8081. Set the maximum scale to one instance; do not enable autoscaling.
4. Set the environment variables below in Koyeb's service settings. Use the platform's secret inputs for credentials.
   Do not put connection strings, passwords or bootstrap credentials in GitHub.
   
Create the service from the repository's root `Dockerfile` and set these environment variables. They come from
the platform's secret settings; **never commit them**.

| Variable | Value |
|---|---|
| `SPRING_PROFILES_ACTIVE` | `prod` |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | from the existing Neon production branch (a development password is refused) |
| `CLINICIT_CORS_ALLOWED_ORIGINS` | the web app's exact origin, e.g. `https://clinic.example.com` (https, no wildcards) |
| `CLINICIT_PUBLIC_APP_URL` | the same origin: patients' status links are `{this}/status/{code}` |
| `CLINICIT_NOTIFICATIONS_ENABLED` / `CLINICIT_NOTIFICATIONS_PROVIDER` | `false` / `none` (no real SMS provider ships yet) |
| `CLINICIT_ML_BASE_URL` | optional: the ML service's private URL (step 4) |
| `CLINICIT_BOOTSTRAP_CLINIC_NAME`, `…_CLINIC_TIMEZONE`, `…_ADMIN_EMAIL`, `…_ADMIN_PASSWORD` | **first start only** (empty database): the clinic and its first admin. Remove them afterwards |

- **Missing or unsafe settings.** If anything is missing or unsafe, the API **refuses to start** and lists every
  problem in the log (`ProductionConfigurationCheck`). Fix them all and redeploy.
- **Demo data.** Never set `CLINICIT_DEMO_ENABLED` here; production refuses it.
- **Ports.** The platform sets `PORT`, and the API listens on it. Probes and metrics are on `MANAGEMENT_PORT` (8081),
  which a single-port web service does not expose publicly.
  - **Health check path:** use the public `GET /api/v1/health` (the server answers). It does not check the database.
  - **Private network:** where the platform offers one, point internal monitoring at
    `:8081/actuator/health/readiness` and Prometheus at `:8081/actuator/prometheus`.
  - **Never** make 8081 public.
- **Proxies.** The API trusts `X-Forwarded-For` only from private-range proxies (Tomcat's default). After deploying,
  sign in with a wrong password from your own machine. The login throttling's per-address counter must see your
  address, not the platform's proxy. If it sees the proxy, set `server.tomcat.remoteip.internal-proxies` to the
  platform's documented proxy range.
- **WebSockets.** Koyeb Web Services support HTTP and WebSocket traffic. Expose only port 8080 publicly; keep
  management port 8081 private. Configure one instance only because the queue's live broker is in memory.

## 4. ML service (optional)

- Deploy `ml/Dockerfile` as a **private** service, reachable only from the API. The image trains its model from the
  committed dataset while building and listens on port 8000.
- Set `CLINICIT_ML_BASE_URL=http://<private-host>:8000` on the API.
- Without the service, estimates use the deterministic baseline and everything else works
  ([AI.md](AI.md#fallback-behaviour)).

## 5. Web app on Vercel

1. The existing Vercel project is `clinic-it`; its Root Directory should be `frontend` and Framework Preset Next.js
   (Node.js 22). Git pushes to `main` deploy to production.
2. After Koyeb creates the API's actual public HTTPS hostname, set `NEXT_PUBLIC_API_BASE_URL` in the Vercel
   project's Production and Preview environments to that exact URL. Do not use a guessed hostname. It is
   compiled into the browser code and into the Content-Security-Policy, so **redeploy after changing it**.
   - The WebSocket URL is derived from it (`https://…` → `wss://…/ws`); there is no separate variable.
   - Without it, a production build has no API: the sign-in page says the site is not connected to a server and
     nothing calls localhost.
   - A Vercel production build with a plain `http://` API URL fails on purpose: browsers block it from an HTTPS page.
3. Add your domain, then make sure the API's `CLINICIT_CORS_ALLOWED_ORIGINS` and `CLINICIT_PUBLIC_APP_URL` are that
   exact origin.

## 6. First sign-in and checklist

1. Open the web app and sign in as the bootstrap admin. Change the password (click your name at the top right, then
   **Change password**; this signs you out everywhere), then remove the `CLINICIT_BOOTSTRAP_*` variables.
2. Under **Team**: add the doctors, and create receptionist and doctor logins with initial passwords, shared
   privately. Each person changes theirs on first sign-in the same way.
3. Under **Schedules**: set each doctor's week and leave.
4. Check:
   - [ ] the API log shows `Started ClinicITApplication` and no configuration refusal;
   - [ ] the reception screen shows **Live** (WebSocket connected through the platform);
   - [ ] a test walk-in's status link opens on a phone, over HTTPS, and updates when called;
   - [ ] `GET https://api…/actuator/prometheus` is **not** reachable from the internet;
   - [ ] backups are on, and a restore has been tried once.

## When it fails clearly

| Symptom | Cause |
|---|---|
| API exits at start with "Production configuration is not safe to start" | a missing or unsafe setting; the message lists all of them |
| Sign-in page says "not connected to a ClinicIT server yet" | `NEXT_PUBLIC_API_BASE_URL` was not set for that build; set it and redeploy |
| Browser shows "Cannot reach the ClinicIT server" | `NEXT_PUBLIC_API_BASE_URL` wrong, or the API's CORS origin does not match the web app's exact origin |
| Reception shows "Reconnecting…" forever | WebSockets blocked by a proxy, or the API URL is not HTTPS/WSS |
| Readiness 503 | the API cannot reach PostgreSQL (URL, TLS mode, credentials, network) |
| Estimates are all "Rough estimate (patients ahead × average consultation)" | the ML service is unset or unreachable, so the baseline is used; nothing else is affected |
