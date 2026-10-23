# Deploying ClinicIT

For a public evaluation deployment that shows the fictional demo clinic read-only, also see [PREVIEW.md](PREVIEW.md).

The selected free-first deployment target is:
- **Frontend:** Vercel (`frontend/`, already deployed)
- **API:** Koyeb Web Service built from the repository-root `Dockerfile`
- **Database:** the existing Neon PostgreSQL project/production branch
- **ML:** omitted initially; the deterministic wait-time fallback is built in

Koyeb documents one free Web Service per organization (512 MB RAM, 0.1 vCPU, 2 GB SSD); it sleeps after one hour
without traffic. **Expect slow cold starts:** with the free instance's 0.1 vCPU, the API needs about three
minutes to start (measured locally with the same CPU and memory limits: about 175 s on an empty database, 302 MB peak
memory). The workflow therefore gives the health check a 600-second grace period. After the service sleeps, the first
request waits for the same cold start. Koyeb may require a payment method during account validation, so do not proceed with any paid plan or
add a card if that conflicts with your free-first constraint. The free instance is intended for preview/hobby use, not
a production clinic with real patient data. See the [Koyeb pricing FAQ](https://www.koyeb.com/docs/faqs/pricing)
and [instance limits](https://www.koyeb.com/docs/reference/instances).

The repository's root Dockerfile and production configuration are ready for a container-based API deployment. The
full hosted stack is **not yet deployed or verified**; CI's Docker Compose smoke test is a local-stack check, not proof
of a public deployment. The requirements and safeguards are in [OPERATIONS.md](OPERATIONS.md).

## Alternative: Render (preview)

If Koyeb is not an option, [`render.yaml`](../render.yaml) deploys the same Docker image as a free Render web service
in [preview mode](PREVIEW.md) (fictional data, read-only):

1. Create an **empty** PostgreSQL database (for example a new Neon or Supabase project) and note its direct,
   non-pooled JDBC URL, user and password. Flyway creates the schema on first start.
2. In Render: **New, Blueprint**, select `Rrachet/ClinicIT`, and enter `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` and a
   `CLINICIT_DEMO_PASSWORD` (12+ characters, shared publicly).
3. When it is live, set the web app's API URL on Vercel to the Render address and redeploy.

Render's free instance sleeps after inactivity and needs several minutes to start cold, like Koyeb's. It is untested
here: the blueprint has not been run on Render.

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
- Keep `DB_POOL_SIZE` (default 10) within Neon connection limits.
- Review the recovery/history retention included with the current Neon plan before using any non-demo data. ClinicIT
  does not create database backups. This free-first deployment is for evaluation with fictional data only.

Flyway creates the schema on the API's first start. Hibernate never changes it.

## 3. API on Koyeb

### Automated bootstrap (recommended)

The repository includes [`.github/workflows/deploy-koyeb.yml`](../.github/workflows/deploy-koyeb.yml), a manual workflow that creates or updates the Koyeb API service, waits for deployment, checks the public health endpoint, and removes the first-start bootstrap variables after a successful health check.

1. Sign in to the [Koyeb control panel](https://app.koyeb.com/), select the free plan/instance if available, and authorize Koyeb's GitHub App to access `Rrachet/ClinicIT`. If account validation requires a payment card, stop—do not add one or select a paid plan.
2. Create a Koyeb API token from [Koyeb API settings](https://app.koyeb.com/user/settings/api/).
3. In GitHub, open **Settings → Secrets and variables → Actions → New repository secret** and add these four secrets:
   - `KOYEB_API_TOKEN`: the Koyeb API token.
   - `CLINICIT_DB_PASSWORD`: the password for the existing Neon `neondb_owner` role.
   - `CLINICIT_BOOTSTRAP_ADMIN_EMAIL`: the email address you will use for the initial ClinicIT administrator.
   - `CLINICIT_BOOTSTRAP_ADMIN_PASSWORD`: a unique password of at least 16 characters, without spaces.
   
   Never commit these values to files or paste them into issues or chat. The workflow uses GitHub Actions secrets to configure the service; the bootstrap credentials are removed from the Koyeb service after the first successful deployment. The database password remains a deployment secret.
4. Open **Actions → Bootstrap ClinicIT API on Koyeb → Run workflow**. It uses the existing Neon project/production branch, deploys the root `Dockerfile`, exposes only port 8080, limits the service to one instance, disables notifications, and leaves the optional ML service off.
5. When the workflow succeeds, copy the exact API URL from the workflow summary. If it fails, inspect its step output and the Koyeb build/runtime logs before rerunning. Do not repeatedly run the bootstrap workflow after a partial failure without checking whether the Koyeb app/service already exists.

The workflow uses the existing production frontend origin `https://clinic-it-kappa.vercel.app` for CORS and patient status links. If you change the frontend domain, update the API's `CLINICIT_CORS_ALLOWED_ORIGINS` and `CLINICIT_PUBLIC_APP_URL` to that exact origin.

### Manual alternative

1. Create a Web Service from `Rrachet/ClinicIT`, branch `main`, with **Dockerfile** as the builder and the repository-root `Dockerfile`.
2. Select the `free` instance if available. Expose **8080 / HTTP** at route `/`; do not expose 8081. Set the maximum scale to one instance and disable autoscaling.
3. Set the environment variables below in Koyeb's service settings. Store credentials using Koyeb's secret inputs, not in the repository.

| Variable | Value |
|---|---|
| `SPRING_PROFILES_ACTIVE` | `prod` |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | from the existing Neon production branch (a development password is refused) |
| `CLINICIT_CORS_ALLOWED_ORIGINS` | the web app's exact origin, e.g. `https://clinic-it-kappa.vercel.app` (https, no wildcards) |
| `CLINICIT_PUBLIC_APP_URL` | the same origin: patients' status links are `{this}/status/{code}` |
| `CLINICIT_NOTIFICATIONS_ENABLED` / `CLINICIT_NOTIFICATIONS_PROVIDER` | `false` / `none` (no real SMS provider ships yet) |
| `CLINICIT_ML_BASE_URL` | optional: the ML service's private URL (step 4) |
| `CLINICIT_BOOTSTRAP_CLINIC_NAME`, `…_CLINIC_TIMEZONE`, `…_ADMIN_EMAIL`, `…_ADMIN_PASSWORD` | **first start only** (empty database): the clinic and its first admin. Remove them after the first successful start |

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
   - [ ] Neon recovery/history options have been reviewed and a restore process tested before any real data is considered.

## When it fails clearly

| Symptom | Cause |
|---|---|
| API exits at start with "Production configuration is not safe to start" | a missing or unsafe setting; the message lists all of them |
| Sign-in page says "not connected to a ClinicIT server yet" | `NEXT_PUBLIC_API_BASE_URL` was not set for that build; set it and redeploy |
| Browser shows "Cannot reach the ClinicIT server" | `NEXT_PUBLIC_API_BASE_URL` wrong, or the API's CORS origin does not match the web app's exact origin |
| Reception shows "Reconnecting…" forever | WebSockets blocked by a proxy, or the API URL is not HTTPS/WSS |
| Readiness 503 | the API cannot reach PostgreSQL (URL, TLS mode, credentials, network) |
| Estimates are all "Rough estimate (patients ahead × average consultation)" | the ML service is unset or unreachable, so the baseline is used; nothing else is affected |
