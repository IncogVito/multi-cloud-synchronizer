#!/bin/bash
# Unmount the iPhone from IPHONE_MOUNT_PATH.
# Output: JSON {"unmounted": bool, "error": str|null}

set -euo pipefail

MOUNT_PATH="${IPHONE_MOUNT_PATH:-/mnt/iphone}"

emit() {
    local unmounted="$1" error="$2"
    printf '{"unmounted": %s, "error": %s}\n' "$unmounted" "$error"
}

# Not mounted — idempotent
if ! mountpoint -q "$MOUNT_PATH" 2>/dev/null; then
    emit true null
    exit 0
fi

ERR_FILE=$(mktemp)
if fusermount -u "$MOUNT_PATH" 2>"$ERR_FILE" || umount "$MOUNT_PATH" 2>"$ERR_FILE"; then
    rm -f "$ERR_FILE"
    emit true null
else
    ERR=$(sed 's/"/'"'"'/g' "$ERR_FILE" | tr -d '\n')
    rm -f "$ERR_FILE"
    emit false "\"$ERR\""
    exit 1
fi
