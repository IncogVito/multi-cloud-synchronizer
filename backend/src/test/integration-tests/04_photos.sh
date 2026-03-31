#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib.sh"

section "GET /api/photos – empty state"

curl_json GET /api/photos
assert_status 200 "$HTTP_CODE" "GET /api/photos returns 200"
assert_contains '"photos"' "$BODY" "Response has photos array"
assert_contains '"total"' "$BODY" "Response has total field"
assert_contains '"page"' "$BODY" "Response has page field"

section "GET /api/photos – pagination params"

curl_json GET "/api/photos?page=0&size=5"
assert_status 200 "$HTTP_CODE" "GET /api/photos?page=0&size=5"
assert_contains '"size"' "$BODY" "Response has size field"

section "GET /api/photos/{id} – not found"

curl_json GET /api/photos/nonexistent-id-123
assert_status 404 "$HTTP_CODE" "GET /api/photos/nonexistent returns 404"

section "GET /api/photos/{id}/thumbnail – not found"

CODE=$(curl -s -o /dev/null -w "%{http_code}" $AUTH "${BASE_URL}/api/photos/nonexistent-id-123/thumbnail")
assert_status 404 "$CODE" "GET /api/photos/nonexistent/thumbnail returns 404"

section "GET /api/photos/{id}/full – not found"

CODE=$(curl -s -o /dev/null -w "%{http_code}" $AUTH "${BASE_URL}/api/photos/nonexistent-id-123/full")
assert_status 404 "$CODE" "GET /api/photos/nonexistent/full returns 404"

section "POST /api/photos/sync – no account"

curl_json POST "/api/photos/sync?accountId=nonexistent-account"
if [ "$HTTP_CODE" -ge 400 ]; then
    pass "POST /api/photos/sync with bad accountId returns error (HTTP $HTTP_CODE)"
else
    fail "POST /api/photos/sync" "Expected 4xx, got HTTP $HTTP_CODE"
fi

section "DELETE /api/photos/icloud – photo not found"

curl_json DELETE "/api/photos/icloud?accountId=fake" '{"photoIds":["fake-photo-id"]}'
if [ "$HTTP_CODE" -ge 400 ]; then
    pass "DELETE /api/photos/icloud with bad photoId returns error (HTTP $HTTP_CODE)"
else
    fail "DELETE /api/photos/icloud" "Expected 4xx, got HTTP $HTTP_CODE"
fi

summary
