#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib.sh"

section "POST /api/agent/detect-drive – SSE stream"

TMP=$(mktemp)
HTTP_CODE=$(curl -s -o "$TMP" -w "%{http_code}" --max-time 15 $AUTH \
    -H "Accept: text/event-stream" \
    -X POST "${BASE_URL}/api/agent/detect-drive" || echo "000")
BODY=$(cat "$TMP")
rm -f "$TMP"

assert_status 200 "$HTTP_CODE" "POST /api/agent/detect-drive returns 200"

section "SSE stream contains expected event structure"

if echo "$BODY" | grep -q "agent-step"; then
    pass "SSE stream contains 'agent-step' events"
else
    fail "SSE events" "Expected 'agent-step' events, got: $(echo "$BODY" | head -5)"
fi

if echo "$BODY" | grep -q '"action"'; then
    pass "SSE events contain 'action' field"
else
    fail "SSE event structure" "Expected 'action' field in events, got: $(echo "$BODY" | head -5)"
fi

section "SSE stream ends with final event"

if echo "$BODY" | grep -q '"final"' || echo "$BODY" | grep -q '"success"'; then
    pass "SSE stream contains final event"
else
    fail "SSE final event" "No final event found. Body: $(echo "$BODY" | tail -5)"
fi

section "Agent endpoint – unauthenticated"

CODE=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 \
    -H "Accept: text/event-stream" \
    -X POST "${BASE_URL}/api/agent/detect-drive")
assert_status 401 "$CODE" "POST /api/agent/detect-drive without auth returns 401"

summary
