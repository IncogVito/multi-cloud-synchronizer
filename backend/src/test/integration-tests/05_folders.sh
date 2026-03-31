#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib.sh"

section "GET /api/folders – empty state"

curl_json GET /api/folders
assert_status 200 "$HTTP_CODE" "GET /api/folders returns 200"
if echo "$BODY" | grep -qE '^\['; then
    pass "GET /api/folders returns JSON array"
else
    fail "GET /api/folders" "Expected JSON array, got: $BODY"
fi

section "POST /api/folders – create root folder"

curl_json POST /api/folders '{"name":"Test Root","folderType":"CUSTOM"}'
assert_status 201 "$HTTP_CODE" "POST /api/folders returns 201"
assert_contains '"id"' "$BODY" "Created folder has id"
assert_contains '"Test Root"' "$BODY" "Created folder has correct name"
assert_contains '"CUSTOM"' "$BODY" "Created folder has correct type"

FOLDER_ID=$(echo "$BODY" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "  Created folder id: $FOLDER_ID"

section "POST /api/folders – create child folder"

curl_json POST /api/folders "{\"name\":\"Child Folder\",\"parentId\":\"${FOLDER_ID}\",\"folderType\":\"CUSTOM\"}"
assert_status 201 "$HTTP_CODE" "POST /api/folders (child) returns 201"
assert_contains '"Child Folder"' "$BODY" "Child folder has correct name"

CHILD_ID=$(echo "$BODY" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

section "GET /api/folders – tree includes created folders"

curl_json GET /api/folders
assert_status 200 "$HTTP_CODE" "GET /api/folders after creates"
assert_contains '"Test Root"' "$BODY" "Tree contains root folder"
assert_contains '"Child Folder"' "$BODY" "Tree contains child folder"

section "PUT /api/folders/{id} – update folder name"

curl_json PUT "/api/folders/${FOLDER_ID}" '{"name":"Renamed Root"}'
assert_status 200 "$HTTP_CODE" "PUT /api/folders/{id} returns 200"
assert_contains '"Renamed Root"' "$BODY" "Folder name updated"

section "PUT /api/folders/{id} – not found"

curl_json PUT /api/folders/nonexistent-folder '{"name":"x"}'
assert_status 404 "$HTTP_CODE" "PUT /api/folders/nonexistent returns 404"

section "GET /api/folders/{id}/photos – empty"

curl_json GET "/api/folders/${FOLDER_ID}/photos"
assert_status 200 "$HTTP_CODE" "GET /api/folders/{id}/photos returns 200"
if echo "$BODY" | grep -qE '^\['; then
    pass "GET /api/folders/{id}/photos returns JSON array"
else
    fail "GET /api/folders/{id}/photos" "Expected JSON array, got: $BODY"
fi

section "POST /api/folders/{id}/photos – assign"

curl_json POST "/api/folders/${FOLDER_ID}/photos" '{"photoIds":["fake-1","fake-2"]}'
assert_status 200 "$HTTP_CODE" "POST /api/folders/{id}/photos returns 200"
assert_contains '"assigned"' "$BODY" "Response has assigned count"

section "POST /api/folders/auto-organize"

curl_json POST "/api/folders/auto-organize?granularity=YEAR"
assert_status 200 "$HTTP_CODE" "POST /api/folders/auto-organize?granularity=YEAR"
assert_contains '"status"' "$BODY" "Response has status field"
assert_contains '"done"' "$BODY" "Status is done"

curl_json POST "/api/folders/auto-organize?granularity=MONTH"
assert_status 200 "$HTTP_CODE" "POST /api/folders/auto-organize?granularity=MONTH"

section "DELETE /api/folders/{id} – delete child then root"

curl_json DELETE "/api/folders/${CHILD_ID}"
assert_status 204 "$HTTP_CODE" "DELETE /api/folders/{child} returns 204"

curl_json DELETE "/api/folders/${FOLDER_ID}"
assert_status 204 "$HTTP_CODE" "DELETE /api/folders/{root} returns 204"

section "DELETE /api/folders/{id} – not found"

curl_json DELETE "/api/folders/${FOLDER_ID}"
assert_status 404 "$HTTP_CODE" "DELETE already-deleted folder returns 404"

summary
