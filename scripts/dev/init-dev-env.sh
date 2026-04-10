#!/bin/bash
# Initializes the local dev environment.
# Run this once after cloning, or after "docker compose down -v" recreates directories.
#
# Usage: ./scripts/dev/init-dev-env.sh
# Requires: sudo (directories are created by Docker daemon as root)

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"

fix_perms() {
    local dir="$1"
    local desc="$2"
    echo "==> $desc: $dir"
    mkdir -p "$dir" 2>/dev/null || true
    if [ "$(stat -c '%a' "$dir" 2>/dev/null)" = "777" ]; then
        echo "    Already 777, skipping."
    else
        sudo chmod 777 "$dir"
        echo "    Done."
    fi
}

# backend/compose.dev.yaml uses ./dev-drive as the mock external drive
fix_perms "$ROOT_DIR/backend/dev-drive" "backend dev-drive (backend-only compose)"

# compose.dev.yaml (full stack) binds /mnt/external-drive from host
fix_perms "/mnt/external-drive" "host /mnt/external-drive (full-stack compose)"

echo ""
echo "Dev environment ready. You can now run:"
echo "  docker compose -f compose.dev.yaml up --build          # full stack"
echo "  docker compose -f backend/compose.dev.yaml up --build  # backend only"
