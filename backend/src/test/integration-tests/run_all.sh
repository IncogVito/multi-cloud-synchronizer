#!/usr/bin/env bash
# Run all integration tests
# Usage:
#   ./run_all.sh
#   BASE_URL=http://localhost:8080 ICLOUD_URL=http://localhost:8000 ./run_all.sh
#   ICLOUD_APPLE_ID=me@icloud.com ICLOUD_PASSWORD=secret ./run_all.sh
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib.sh"

echo "=============================="
echo " Integration Tests"
echo " Backend:       ${BASE_URL}"
echo " iCloud svc:    ${ICLOUD_URL}"
echo "=============================="

wait_for_backend

TOTAL_PASS=0
TOTAL_FAIL=0
FAILED_SUITES=()

run_suite() {
    local script=$1
    local name
    name=$(basename "$script")
    echo ""
    echo ">>> Running: $name"
    echo "-------------------------------"

    # Run in subshell so summary/exit doesn't abort run_all
    set +e
    bash "$script"
    local exit_code=$?
    set -e

    if [ $exit_code -eq 0 ]; then
        echo -e "    ${GREEN}Suite PASSED${NC}"
    else
        echo -e "    ${RED}Suite FAILED${NC}"
        FAILED_SUITES+=("$name")
    fi
}

for script in "$SCRIPT_DIR"/0*.sh; do
    run_suite "$script"
done

echo ""
echo "=============================="
echo " Final Summary"
echo "=============================="
if [ ${#FAILED_SUITES[@]} -eq 0 ]; then
    echo -e "${GREEN}All suites passed.${NC}"
    exit 0
else
    echo -e "${RED}Failed suites:${NC}"
    for s in "${FAILED_SUITES[@]}"; do
        echo -e "  ${RED}- $s${NC}"
    done
    exit 1
fi
