#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib.sh"

section "Health endpoint"

curl_json GET /api/health
assert_status 200 "$HTTP_CODE" "GET /api/health"
assert_contains '"status"' "$BODY" "Response has status field"
assert_contains '"ok"' "$BODY" "Status is ok"
assert_contains '"version"' "$BODY" "Response has version field"

section "Health – unauthenticated access allowed"

CODE=$(curl -s -o /dev/null -w "%{http_code}" -X GET "${BASE_URL}/api/health")
assert_status 200 "$CODE" "GET /api/health without credentials"

section "Micronaut management health"

CODE=$(curl -s -o /dev/null -w "%{http_code}" "${BASE_URL}/health")
assert_status 200 "$CODE" "GET /health (management endpoint)"

summary
