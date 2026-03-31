#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${ICLOUD_SERVICE_URL:-http://localhost:8000}"

echo "=== iCloud Service E2E Test 2 – Devices & Photos ==="
echo "Base URL: $BASE_URL"
echo

# --- Credentials ---
read -rp "Apple ID: " APPLE_ID
read -rsp "Password: " PASSWORD
echo

# --- Login ---
echo
echo ">>> POST /auth/login"
LOGIN_RESPONSE=$(curl -sf -X POST "$BASE_URL/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"apple_id\": \"$APPLE_ID\", \"password\": \"$PASSWORD\"}")

echo "Response: $LOGIN_RESPONSE"

SESSION_ID=$(echo "$LOGIN_RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin)['session_id'])")
REQUIRES_2FA=$(echo "$LOGIN_RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin)['requires_2fa'])")

echo "Session ID: $SESSION_ID"
echo "Requires 2FA: $REQUIRES_2FA"

# --- 2FA ---
if [ "$REQUIRES_2FA" = "True" ]; then
  echo
  read -rp "2FA code: " CODE

  echo
  echo ">>> POST /auth/2fa"
  TWO_FA_RESPONSE=$(curl -sf -X POST "$BASE_URL/auth/2fa" \
    -H "Content-Type: application/json" \
    -d "{\"session_id\": \"$SESSION_ID\", \"code\": \"$CODE\"}")

  echo "Response: $TWO_FA_RESPONSE"

  AUTHENTICATED=$(echo "$TWO_FA_RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin)['authenticated'])")
  if [ "$AUTHENTICATED" != "True" ]; then
    echo "ERROR: 2FA failed."
    exit 1
  fi
fi

PHOTOS_TIMEOUT="${PHOTOS_TIMEOUT:-120}"

# --- Devices ---
echo
echo ">>> GET /devices"
curl -sf --max-time 30 "$BASE_URL/devices" \
  -H "X-Session-ID: $SESSION_ID" | python3 -m json.tool

# --- Photos (50, metadata only, no download) ---
echo
echo ">>> GET /photos?limit=50&offset=0  (timeout: ${PHOTOS_TIMEOUT}s)"
echo "    (icloudpy may need to scan the full library before returning – this can take a while)"
if curl -sf --max-time "$PHOTOS_TIMEOUT" "$BASE_URL/photos?limit=50&offset=0" \
  -H "X-Session-ID: $SESSION_ID" | python3 -m json.tool; then
  :
else
  echo "WARN: photos request timed out or failed (exit $?). Try: PHOTOS_TIMEOUT=300 $0"
fi

echo
echo "=== Done ==="
