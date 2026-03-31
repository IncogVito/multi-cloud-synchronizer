#!/usr/bin/env bash
# Requires: ICLOUD_APPLE_ID, ICLOUD_PASSWORD env vars
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib.sh"

if [ -z "${ICLOUD_APPLE_ID:-}" ]; then
    echo -n "iCloud Apple ID (email): "
    read -r ICLOUD_APPLE_ID
fi

if [ -z "${ICLOUD_PASSWORD:-}" ]; then
    echo -n "iCloud password: "
    read -rs ICLOUD_PASSWORD
    echo
fi

section "iCloud login via backend"

curl_json POST /api/accounts/login \
    "{\"appleId\":\"${ICLOUD_APPLE_ID}\",\"password\":\"${ICLOUD_PASSWORD}\"}"

if [ "$HTTP_CODE" -ge 500 ]; then
    fail "POST /api/accounts/login" "Server error: HTTP $HTTP_CODE – $BODY"
    summary
fi

assert_status 200 "$HTTP_CODE" "POST /api/accounts/login"
assert_contains '"accountId"' "$BODY" "Login response has accountId"
assert_contains '"sessionId"' "$BODY" "Login response has sessionId"

ACCOUNT_ID=$(echo "$BODY" | grep -o '"accountId":"[^"]*"' | cut -d'"' -f4)
REQUIRES_2FA=$(echo "$BODY" | grep -o '"requires2fa":[^,}]*' | cut -d':' -f2 | tr -d ' ')

echo "  Account ID: $ACCOUNT_ID"
echo "  Requires 2FA: $REQUIRES_2FA"

section "2FA (if required)"

if [ "$REQUIRES_2FA" = "true" ]; then
    if [ -z "${ICLOUD_2FA_CODE:-}" ]; then
        echo -e "${YELLOW}  2FA required but ICLOUD_2FA_CODE not set – enter code:${NC}"
        read -r ICLOUD_2FA_CODE
    fi
    curl_json POST /api/accounts/2fa \
        "{\"accountId\":\"${ACCOUNT_ID}\",\"code\":\"${ICLOUD_2FA_CODE}\"}"
    assert_status 200 "$HTTP_CODE" "POST /api/accounts/2fa"
    assert_contains '"success":true' "$BODY" "2FA succeeded"
fi

section "GET /api/accounts – account visible"

curl_json GET /api/accounts
assert_status 200 "$HTTP_CODE" "GET /api/accounts"
assert_contains "$ACCOUNT_ID" "$BODY" "Account appears in list"

section "GET /api/accounts/{id}/status"

curl_json GET "/api/accounts/${ACCOUNT_ID}/status"
assert_status 200 "$HTTP_CODE" "GET /api/accounts/{id}/status"
assert_contains '"hasActiveSession":true' "$BODY" "Account has active session"

section "POST /api/photos/sync"

echo "  Triggering sync (this may take a while)..."
curl_json POST "/api/photos/sync?accountId=${ACCOUNT_ID}"
assert_status 200 "$HTTP_CODE" "POST /api/photos/sync"
assert_contains '"synced"' "$BODY" "Sync response has synced count"

SYNCED=$(echo "$BODY" | grep -o '"synced":[0-9]*' | cut -d':' -f2)
echo "  Synced photos: $SYNCED"

section "GET /api/photos after sync"

curl_json GET "/api/photos?accountId=${ACCOUNT_ID}&synced=true"
assert_status 200 "$HTTP_CODE" "GET /api/photos after sync"
assert_contains '"photos"' "$BODY" "Response has photos array"

TOTAL=$(echo "$BODY" | grep -o '"total":[0-9]*' | cut -d':' -f2)
echo "  Total synced photos: $TOTAL"

if [ "${TOTAL:-0}" -gt 0 ]; then
    PHOTO_ID=$(echo "$BODY" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
    echo "  First photo ID: $PHOTO_ID"

    section "GET /api/photos/{id}"

    curl_json GET "/api/photos/${PHOTO_ID}"
    assert_status 200 "$HTTP_CODE" "GET /api/photos/{id}"
    assert_contains '"syncedToDisk":true' "$BODY" "Photo is marked synced to disk"

    section "GET /api/photos/{id}/thumbnail"

    CODE=$(curl -s -o /dev/null -w "%{http_code}" $AUTH "${BASE_URL}/api/photos/${PHOTO_ID}/thumbnail")
    if [ "$CODE" -eq 200 ]; then
        pass "GET /api/photos/{id}/thumbnail returns 200"
    elif [ "$CODE" -eq 404 ]; then
        pass "GET /api/photos/{id}/thumbnail returns 404 (thumbnail async – ok)"
    else
        fail "GET /api/photos/{id}/thumbnail" "Unexpected HTTP $CODE"
    fi
fi

section "Cleanup – delete account"

curl_json DELETE "/api/accounts/${ACCOUNT_ID}"
assert_status 204 "$HTTP_CODE" "DELETE /api/accounts/{id} returns 204"

curl_json GET "/api/accounts/${ACCOUNT_ID}/status"
assert_status 404 "$HTTP_CODE" "GET deleted account returns 404"

summary
