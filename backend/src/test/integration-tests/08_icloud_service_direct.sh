#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib.sh"

section "icloud-service health"

curl_icloud GET /health
assert_status 200 "$HTTP_CODE" "GET ${ICLOUD_URL}/health"
assert_contains '"status"' "$BODY" "Health response has status"

section "icloud-service – login with bad credentials"

curl_icloud POST /auth/login '{"apple_id":"fake@icloud.com","password":"badpass"}'
if [ "$HTTP_CODE" -ge 400 ]; then
    pass "POST /auth/login with bad creds returns error (HTTP $HTTP_CODE)"
else
    fail "POST /auth/login" "Expected 4xx error, got HTTP $HTTP_CODE: $BODY"
fi

section "icloud-service – list sessions"

curl_icloud GET /auth/sessions
assert_status 200 "$HTTP_CODE" "GET /auth/sessions"
if echo "$BODY" | grep -qE '^\['; then
    pass "GET /auth/sessions returns JSON array"
else
    fail "GET /auth/sessions" "Expected JSON array, got: $BODY"
fi

section "icloud-service – get photos without valid session"

TMP=$(mktemp)
CODE=$(curl -s -o "$TMP" -w "%{http_code}" -X GET "${ICLOUD_URL}/photos" \
    -H "X-Session-ID: nonexistent-session")
rm -f "$TMP"
if [ "$CODE" -ge 400 ]; then
    pass "GET /photos with nonexistent session returns error (HTTP $CODE)"
else
    fail "GET /photos" "Expected 4xx error, got HTTP $CODE"
fi

section "icloud-service – delete nonexistent session (idempotent)"

TMP=$(mktemp)
CODE=$(curl -s -o "$TMP" -w "%{http_code}" -X DELETE "${ICLOUD_URL}/auth/sessions/nonexistent-session-id")
rm -f "$TMP"
assert_status 204 "$CODE" "DELETE /auth/sessions/nonexistent returns 204 (idempotent)"

summary
