#!/usr/bin/env bash
# T01 smoke check (spec 13.2): clean Docker Compose startup.
# Builds and starts the full stack, waits for the backend health endpoint
# through the nginx proxy, verifies the SPA is served and the seeded demo
# login works, then tears the stack down (volumes included, so the run is
# clean). Requires Docker; exits non-zero on the first failed check.
#
# Usage: scripts/smoke-compose.sh
set -euo pipefail
cd "$(dirname "$0")/.."

BASE="http://localhost:8080"
COMPOSE="docker compose"

cleanup() {
  echo "== tearing down =="
  $COMPOSE down -v >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "== building and starting the stack =="
$COMPOSE up -d --build

echo "== waiting for backend health via the proxy (up to 180s) =="
healthy=""
for i in $(seq 1 60); do
  if curl -fsS "$BASE/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then
    healthy=1
    break
  fi
  sleep 3
done
if [ -z "$healthy" ]; then
  echo "FAIL: backend did not report UP"
  $COMPOSE logs --tail 50 backend || true
  exit 1
fi
echo "backend health: UP"

echo "== checking the SPA shell is served =="
curl -fsS "$BASE/" | grep -q "<title>MigrateFlow" \
  || { echo "FAIL: SPA index not served at /"; exit 1; }
echo "SPA: served"

echo "== checking demo login through the proxy =="
cookies="$(mktemp)"
csrf="$(curl -fsS -c "$cookies" "$BASE/api/v1/auth/csrf" >/dev/null; awk '$6 == "XSRF-TOKEN" {print $7}' "$cookies")"
status="$(curl -s -o /dev/null -w '%{http_code}' -b "$cookies" -H "X-XSRF-TOKEN: $csrf" \
  -H 'Content-Type: application/json' \
  -d '{"email":"engineer@example.test","password":"engineer-dev-pw"}' \
  "$BASE/api/v1/auth/login")"
rm -f "$cookies"
[ "$status" = "200" ] || { echo "FAIL: demo login returned $status"; exit 1; }
echo "demo login: 200"

echo "SMOKE OK: compose stack healthy, SPA served, login works"
