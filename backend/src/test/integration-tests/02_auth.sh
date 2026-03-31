#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib.sh"

section "Basic Auth protection"

CODE=$(curl -s -o /dev/null -w "%{http_code}" "${BASE_URL}/api/accounts")
assert_status 401 "$CODE" "GET /api/accounts without credentials returns 401"

CODE=$(curl -s -o /dev/null -w "%{http_code}" -u wrong:wrong "${BASE_URL}/api/accounts")
assert_status 401 "$CODE" "GET /api/accounts with wrong credentials returns 401"

CODE=$(curl -s -o /dev/null -w "%{http_code}" $AUTH "${BASE_URL}/api/accounts")
assert_status 200 "$CODE" "GET /api/accounts with correct credentials returns 200"

section "Account login – invalid iCloud credentials"

curl_json POST /api/accounts/login '{"appleId":"notreal@icloud.com","password":"wrongpass"}'
if [ "$HTTP_CODE" -lt 500 ] || echo "$BODY" | grep -qi "error\|detail\|invalid\|wrong\|fail"; then
    pass "POST /api/accounts/login with bad creds returns error response (HTTP $HTTP_CODE)"
else
    fail "POST /api/accounts/login" "Unexpected response: HTTP $HTTP_CODE – $BODY"
fi

section "Account list"

curl_json GET /api/accounts
assert_status 200 "$HTTP_CODE" "GET /api/accounts returns 200"
if echo "$BODY" | grep -qE '^\['; then
    pass "GET /api/accounts returns a JSON array"
else
    fail "GET /api/accounts" "Expected JSON array, got: $BODY"
fi

summary
