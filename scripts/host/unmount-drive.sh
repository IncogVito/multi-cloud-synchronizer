#!/bin/bash
# Unmount the external drive from /mnt/external-drive.
# Output: JSON {"success": bool, "message": str}

# Requires root. Re-execs itself via sudo if not already running as root.
# Configure passwordless sudo in /etc/sudoers (visudo):
#   incogvito ALL=(root) NOPASSWD: /home/incogvito/docker-apps/multi-cloud-synchronizer/scripts/host/unmount-drive.sh

[ "$(id -u)" -ne 0 ] && exec sudo -n "$0" "$@"

MOUNT_POINT="${1:-/mnt/external-drive}"

if ! mountpoint -q "$MOUNT_POINT" 2>/dev/null; then
    echo "{\"success\": false, \"message\": \"Not mounted at $MOUNT_POINT\"}"
    exit 1
fi

if umount "$MOUNT_POINT" 2>&1; then
    echo "{\"success\": true, \"message\": \"Unmounted $MOUNT_POINT\"}"
else
    ERR=$?
    echo "{\"success\": false, \"message\": \"umount failed with exit code $ERR\"}"
    exit $ERR
fi
