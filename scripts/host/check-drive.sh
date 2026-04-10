#!/bin/bash
# Check if the external drive is available at a given path.
# Usage: ./check-drive.sh [/mnt/external-drive]
# Output: JSON {"available": bool, "path": str|null, "free_bytes": int|null}

MOUNT_PATH="${1:-/mnt/external-drive}"
AVAILABLE=false
FREE_BYTES=null

if [ -d "$MOUNT_PATH" ] && mountpoint -q "$MOUNT_PATH" 2>/dev/null; then
    AVAILABLE=true
    FREE_BYTES=$(df -B1 --output=avail "$MOUNT_PATH" 2>/dev/null | tail -1 | tr -d ' ')
    if [ -z "$FREE_BYTES" ]; then FREE_BYTES=null; fi
    echo "{\"available\": $AVAILABLE, \"path\": \"$MOUNT_PATH\", \"free_bytes\": $FREE_BYTES}"
elif [ -d "$MOUNT_PATH" ]; then
    # Directory exists but not a mount point – treat as available (dev/local mode)
    AVAILABLE=true
    FREE_BYTES=$(df -B1 --output=avail "$MOUNT_PATH" 2>/dev/null | tail -1 | tr -d ' ')
    if [ -z "$FREE_BYTES" ]; then FREE_BYTES=null; fi
    echo "{\"available\": $AVAILABLE, \"path\": \"$MOUNT_PATH\", \"free_bytes\": $FREE_BYTES}"
else
    echo "{\"available\": false, \"path\": null, \"free_bytes\": null}"
fi
