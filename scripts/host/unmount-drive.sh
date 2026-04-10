#!/bin/bash
# Unmount the external drive from /mnt/external-drive.
# Output: JSON {"success": bool, "message": str}

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
