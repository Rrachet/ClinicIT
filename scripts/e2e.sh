#!/usr/bin/env bash
# Runs the browser end-to-end tests against the REAL stack: builds and starts the Python
# wait-time service (ml/, trained from the committed synthetic dataset if no model exists),
# the Spring Boot API (against the PostgreSQL database in DB_URL) and the Next.js frontend,
# runs Playwright, then stops everything. Nothing is mocked.
#
#   DB_URL=jdbc:postgresql://localhost:5432/clinicit_e2e DB_USERNAME=postgres DB_PASSWORD= scripts/e2e.sh
#
# On an empty database the first admin is bootstrapped from E2E_ADMIN_EMAIL / E2E_ADMIN_PASSWORD.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
API_PORT="${API_PORT:-18080}"
WEB_PORT="${WEB_PORT:-3000}"
ML_PORT="${ML_PORT:-18000}"
MANAGEMENT_PORT="${MANAGEMENT_PORT:-18081}"
export E2E_ADMIN_EMAIL="${E2E_ADMIN_EMAIL:-owner@e2e.clinicit.test}"
export E2E_ADMIN_PASSWORD="${E2E_ADMIN_PASSWORD:-e2e-admin-password-1}"
export E2E_API_URL="http://localhost:${API_PORT}"
export E2E_BASE_URL="http://localhost:${WEB_PORT}"
export E2E_ML_URL="http://localhost:${ML_PORT}"
LOGS="${LOGS:-$ROOT/frontend/test-results/stack}"
mkdir -p "$LOGS"

# Each service runs in its own process group (setsid) so cleanup stops the whole tree:
# killing only `npx` would leave next-server/java running and holding the port.
groups=()
cleanup() { for pgid in "${groups[@]}"; do kill -TERM -- "-$pgid" 2>/dev/null || true; done; }
trap cleanup EXIT

port_free() { ! (echo >"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }
for port in "$API_PORT" "$WEB_PORT" "$ML_PORT" "$MANAGEMENT_PORT"; do
  if ! port_free "$port"; then
    echo "Port $port is already in use; stop whatever is running there first." >&2
    exit 1
  fi
done

wait_for() { # url name
  for _ in $(seq 1 90); do curl -sf "$1" >/dev/null && return 0; sleep 1; done
  echo "$2 did not start; see $LOGS" >&2; exit 1
}

echo "Starting the wait-time ML service on :$ML_PORT…"
ML="$ROOT/ml"
if [ ! -x "$ML/.venv/bin/python" ]; then
  python3 -m venv "$ML/.venv" && "$ML/.venv/bin/pip" install -q -r "$ML/requirements-dev.txt"
fi
if [ ! -f "$ML/models/current/model.joblib" ]; then
  (cd "$ML" && .venv/bin/python -m clinicit_ml.train --data data/synthetic_wait_times.csv.gz --out models/current)
fi
(cd "$ML" && CLINICIT_MODEL_DIR=models/current exec setsid .venv/bin/uvicorn clinicit_ml.api:create_default_app \
  --factory --host 127.0.0.1 --port "$ML_PORT" >"$LOGS/ml.log" 2>&1) &
groups+=($!)
wait_for "$E2E_ML_URL/health" "ML service"

echo "Building API…"
(cd "$ROOT" && mvn -q -B -DskipTests package)
echo "Starting API on :$API_PORT…"
PORT="$API_PORT" \
MANAGEMENT_PORT="$MANAGEMENT_PORT" \
CLINICIT_ML_BASE_URL="$E2E_ML_URL" \
CLINICIT_CORS_ALLOWED_ORIGINS="$E2E_BASE_URL" \
CLINICIT_PUBLIC_APP_URL="$E2E_BASE_URL" \
CLINICIT_BOOTSTRAP_CLINIC_NAME="${E2E_CLINIC_NAME:-E2E Clinic}" \
CLINICIT_BOOTSTRAP_ADMIN_EMAIL="$E2E_ADMIN_EMAIL" \
CLINICIT_BOOTSTRAP_ADMIN_PASSWORD="$E2E_ADMIN_PASSWORD" \
  setsid java -jar "$ROOT"/target/clinicit-*.jar >"$LOGS/api.log" 2>&1 &
groups+=($!)
wait_for "http://localhost:$MANAGEMENT_PORT/actuator/health/readiness" "API (readiness)"

echo "Building and starting frontend on :$WEB_PORT…"
(cd "$ROOT/frontend" && NEXT_TELEMETRY_DISABLED=1 NEXT_PUBLIC_API_BASE_URL="$E2E_API_URL" npx next build >"$LOGS/web-build.log" 2>&1)
(cd "$ROOT/frontend" && NEXT_TELEMETRY_DISABLED=1 exec setsid npx next start -p "$WEB_PORT" >"$LOGS/web.log" 2>&1) &
groups+=($!)
wait_for "$E2E_BASE_URL/login" "Frontend"

(cd "$ROOT/frontend" && npx playwright test "$@")
