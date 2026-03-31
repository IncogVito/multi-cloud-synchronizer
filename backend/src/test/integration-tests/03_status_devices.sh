#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib.sh"

section "GET /api/status/devices"

curl_json GET /api/status/devices
assert_status 200 "$HTTP_CODE" "GET /api/status/devices"
assert_contains "EXTERNAL_DRIVE" "$BODY" "Response contains EXTERNAL_DRIVE entry"
assert_contains "IPHONE" "$BODY" "Response contains IPHONE entry"
assert_contains "ICLOUD" "$BODY" "Response contains ICLOUD entry"

section "POST /api/status/check-drive"

curl_json POST /api/status/check-drive
assert_status 200 "$HTTP_CODE" "POST /api/status/check-drive"
assert_contains '"deviceType"' "$BODY" "Response has deviceType field"
assert_contains "EXTERNAL_DRIVE" "$BODY" "Response refers to EXTERNAL_DRIVE"
assert_contains '"lastCheckedAt"' "$BODY" "Response has lastCheckedAt timestamp"

echo "  Drive connected: $(echo "$BODY" | grep -o '"connected":[^,}]*')"

section "POST /api/status/check-iphone"

curl_json POST /api/status/check-iphone
assert_status 200 "$HTTP_CODE" "POST /api/status/check-iphone"
assert_contains "IPHONE" "$BODY" "Response refers to IPHONE"

echo "  iPhone connected: $(echo "$BODY" | grep -o '"connected":[^,}]*')"

section "Device statuses persisted after checks"

curl_json GET /api/status/devices
assert_status 200 "$HTTP_CODE" "GET /api/status/devices after checks"
assert_contains '"lastCheckedAt"' "$BODY" "Devices have lastCheckedAt after check"

summary
