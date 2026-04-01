#!/bin/bash
# Mount a block device at a given mount point.
# Usage: ./mount-drive.sh /dev/sdX /mnt/external-drive
# Output: JSON {"mounted": bool, "device": str, "mount_point": str, "message": str}

DEVICE="${1}"
MOUNT_POINT="${2:-/mnt/external-drive}"

if [ -z "$DEVICE" ]; then
    echo '{"mounted": false, "device": null, "mount_point": null, "message": "No device specified"}'
    exit 1
fi

# Create mount point if missing
mkdir -p "$MOUNT_POINT" 2>/dev/null

# Try to mount
if mount "$DEVICE" "$MOUNT_POINT" 2>/tmp/mount-err; then
    echo "{\"mounted\": true, \"device\": \"$DEVICE\", \"mount_point\": \"$MOUNT_POINT\", \"message\": \"Mounted successfully\"}"
else
    ERR=$(cat /tmp/mount-err | tr '"' "'" | tr '\n' ' ')
    echo "{\"mounted\": false, \"device\": \"$DEVICE\", \"mount_point\": \"$MOUNT_POINT\", \"message\": \"$ERR\"}"
    exit 1
fi
