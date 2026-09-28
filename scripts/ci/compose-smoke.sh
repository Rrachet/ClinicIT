#!/usr/bin/env bash
# Builds and starts the whole stack with compose.yaml, then checks it end to end:
# database and migrations, the bootstrap admin's login, the API calling the ML service,
# and the web app serving pages that point at the API. Tears everything down afterwards.
set -euo pipefail
cd "$(dirname "$0")/../.."

cleanup() {
  status=$?
  if [ $status -ne 0 ]; then docker compose logs --no-color --tail=200 || true; fi
  docker compose down -v --remove-orphans >/dev/null 2>&1 || true
  exit $status
}
trap cleanup EXIT

docker compose up --build --detach --wait --wait-timeout 300

echo "API readiness (inside the compose network)"
docker compose exec -T api bash -c \
  "exec 3<>/dev/tcp/127.0.0.1/8081 && printf 'GET /actuator/health/readiness HTTP/1.0\r\n\r\n' >&3 && cat <&3" \
  | grep -q '"UP"'

echo "Admin login"
login=$(curl -fsS http://127.0.0.1:8080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"admin@demo.clinicit.local","password":"local-demo-only-2026"}')
token=$(printf '%s' "$login" | python3 -c 'import json,sys; print(json.load(sys.stdin)["accessToken"])')
curl -fsS http://127.0.0.1:8080/api/v1/clinic -H "Authorization: Bearer $token" | grep -q 'ClinicIT Demo Clinic, Hyderabad'

echo "ML service reachable from the API container"
docker compose exec -T api bash -c \
  "exec 3<>/dev/tcp/ml/8000 && printf 'GET /health HTTP/1.0\r\nHost: ml\r\n\r\n' >&3 && cat <&3" \
  | grep -q '200 OK'

echo "Web app"
# The web container has no healthcheck: give Next.js a moment to start listening.
for _ in $(seq 1 30); do
  headers=$(curl -fsS -D - -o /dev/null http://127.0.0.1:3000/login 2>/dev/null) && break
  sleep 1
done
printf '%s' "$headers" | grep -qi 'content-security-policy:.*http://localhost:8080'

echo "Management port not published"
if curl -fsS -m 3 http://127.0.0.1:8081/actuator/health >/dev/null 2>&1; then
  echo "8081 must not be reachable from the host" >&2
  exit 1
fi

echo "OK: compose stack is up and working"
