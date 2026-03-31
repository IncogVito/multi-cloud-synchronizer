#!/usr/bin/env bash
# Shared helpers for integration tests

BASE_URL="${BASE_URL:-http://localhost:8080}"
ICLOUD_URL="${ICLOUD_URL:-http://localhost:8000}"
USER="${APP_USERNAME:-admin}"
PASS="${APP_PASSWORD:-changeme}"
AUTH="-u ${USER}:${PASS}"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

PASS_COUNT=0
FAIL_COUNT=0

# Globals set by curl_json / curl_icloud
HTTP_CODE=""
BODY=""

pass() {
    echo -e "${GREEN}  PASS${NC} $1"
    ((PASS_COUNT++)) || true
}

fail() {
    echo -e "${RED}  FAIL${NC} $1"
    echo -e "       ${YELLOW}${2:-}${NC}"
    ((FAIL_COUNT++)) || true
}

section() {
    echo ""
    echo -e "${YELLOW}==> $1${NC}"
}

summary() {
    echo ""
    echo "-----------------------------"
    echo -e "Results: ${GREEN}${PASS_COUNT} passed${NC}, ${RED}${FAIL_COUNT} failed${NC}"
    echo "-----------------------------"
    [ "$FAIL_COUNT" -eq 0 ] && exit 0 || exit 1
}

assert_status() {
    local expected=$1 actual=${2:-} label=${3:-}
    if [ "$actual" = "$expected" ]; then
        pass "$label (HTTP $actual)"
    else
        fail "$label" "Expected HTTP $expected, got HTTP $actual"
    fi
}

assert_contains() {
    local substr=$1 body=${2:-} label=${3:-}
    if echo "$body" | grep -q "$substr"; then
        pass "$label (contains '$substr')"
    else
        fail "$label" "Expected body to contain '$substr', got: $body"
    fi
}

# curl_json <method> <path> [body]
# Sets globals: $HTTP_CODE, $BODY
curl_json() {
    local method=$1 path=$2 body=${3:-}
    local tmp
    tmp=$(mktemp)
    local args=(-s -o "$tmp" -w "%{http_code}" -X "$method" $AUTH \
        -H "Content-Type: application/json" \
        "${BASE_URL}${path}")
    [ -n "$body" ] && args+=(-d "$body")
    HTTP_CODE=$(curl "${args[@]}")
    BODY=$(cat "$tmp")
    rm -f "$tmp"
}

# curl_icloud <method> <path> [body]
# Sets globals: $HTTP_CODE, $BODY
curl_icloud() {
    local method=$1 path=$2 body=${3:-}
    local tmp
    tmp=$(mktemp)
    local args=(-s -o "$tmp" -w "%{http_code}" -X "$method" \
        -H "Content-Type: application/json" \
        "${ICLOUD_URL}${path}")
    [ -n "$body" ] && args+=(-d "$body")
    HTTP_CODE=$(curl "${args[@]}")
    BODY=$(cat "$tmp")
    rm -f "$tmp"
}

wait_for_backend() {
    echo -n "Waiting for backend at ${BASE_URL}..."
    for i in $(seq 1 30); do
        if curl -sf "${BASE_URL}/api/health" > /dev/null 2>&1; then
            echo " ready."
            return 0
        fi
        sleep 2
        echo -n "."
    done
    echo ""
    echo -e "${RED}Backend did not become ready in 60s${NC}"
    exit 1
}
