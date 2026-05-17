#!/bin/bash
# Pre-deployment cleanup: unmount all managed mount points before docker compose down.
# Safe to run multiple times — skips anything not mounted.
# Requires passwordless sudo for umount (configure in /etc/sudoers via visudo):
#   incogvito ALL=(root) NOPASSWD: /usr/bin/umount

set -euo pipefail

IPHONE_MOUNT="${IPHONE_HOST_MOUNT_PATH:-/home/incogvito/docker-apps/multi-cloud-synchronizer/mountable/iphone}"
EXTERNAL_DRIVE_MOUNT="${EXTERNAL_DRIVE_PATH_HOST:-/mnt/external-drive}"

unmount() {
    local path="$1"
    local label="$2"

    if mountpoint -q "$path" 2>/dev/null; then
        echo "[pre-deploy] Unmounting $label at $path ..."
        if fusermount -u "$path" 2>/dev/null; then
            echo "[pre-deploy] $label unmounted (fusermount)."
        elif sudo umount "$path"; then
            echo "[pre-deploy] $label unmounted (umount)."
        else
            echo "[pre-deploy] ERROR: failed to unmount $label at $path" >&2
            exit 1
        fi
    else
        echo "[pre-deploy] $label not mounted at $path — skip."
    fi
}

unmount "$IPHONE_MOUNT" "iPhone"

echo "[pre-deploy] Done."
