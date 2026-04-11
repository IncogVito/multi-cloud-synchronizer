#!/bin/bash
# Mount a block device at a given mount point.
# Usage: ./mount-drive.sh /dev/sdX [/mnt/external-drive]
# Output: JSON {"mounted": bool, "device": str, "mount_point": str, "message": str}
#
# The backend container runs as gradle (uid=1000, gid=1000).
# For FAT32/exFAT/NTFS we pass uid/gid/umask mount options so the container
# user can write to the drive without needing root after mount.
# For ext4/btrfs/xfs ownership is set via chown after mount.

DEVICE="${1}"
MOUNT_POINT="${2:-/mnt/external-drive}"

# UID/GID of the gradle user inside the backend container
CONTAINER_UID=1000
CONTAINER_GID=1000

if [ -z "$DEVICE" ]; then
    echo '{"mounted": false, "device": null, "mount_point": null, "message": "No device specified"}'
    exit 1
fi

# Create mount point if missing
if ! mkdir -p "$MOUNT_POINT" 2>/tmp/mkdir-err; then
    MKDIR_ERR=$(cat /tmp/mkdir-err | tr '"' "'" | tr '\n' ' ')
    echo "{\"mounted\": false, \"device\": \"$DEVICE\", \"mount_point\": \"$MOUNT_POINT\", \"message\": \"Cannot create mount point $MOUNT_POINT: $MKDIR_ERR — try: sudo mkdir -p $MOUNT_POINT\"}"
    exit 1
fi

# Detect filesystem type
FSTYPE=$(blkid -s TYPE -o value "$DEVICE" 2>/dev/null)

case "$FSTYPE" in
    vfat|fat16|fat32|exfat|ntfs|ntfs-3g)
        # These filesystems support uid/gid/umask as mount options
        MOUNT_OPTS="uid=${CONTAINER_UID},gid=${CONTAINER_GID},umask=022"
        if mount -o "$MOUNT_OPTS" "$DEVICE" "$MOUNT_POINT" 2>/tmp/mount-err; then
            echo "{\"mounted\": true, \"device\": \"$DEVICE\", \"mount_point\": \"$MOUNT_POINT\", \"message\": \"Mounted $FSTYPE with uid/gid options\"}"
        else
            ERR=$(cat /tmp/mount-err | tr '"' "'" | tr '\n' ' ')
            echo "{\"mounted\": false, \"device\": \"$DEVICE\", \"mount_point\": \"$MOUNT_POINT\", \"message\": \"$ERR\"}"
            exit 1
        fi
        ;;
    ext2|ext3|ext4|btrfs|xfs|f2fs)
        # POSIX filesystems: mount normally then chown the root
        if mount "$DEVICE" "$MOUNT_POINT" 2>/tmp/mount-err; then
            chown "${CONTAINER_UID}:${CONTAINER_GID}" "$MOUNT_POINT"
            echo "{\"mounted\": true, \"device\": \"$DEVICE\", \"mount_point\": \"$MOUNT_POINT\", \"message\": \"Mounted $FSTYPE and chowned mount point\"}"
        else
            ERR=$(cat /tmp/mount-err | tr '"' "'" | tr '\n' ' ')
            echo "{\"mounted\": false, \"device\": \"$DEVICE\", \"mount_point\": \"$MOUNT_POINT\", \"message\": \"$ERR\"}"
            exit 1
        fi
        ;;
    *)
        # Unknown filesystem — try plain mount, then chown as fallback
        if mount "$DEVICE" "$MOUNT_POINT" 2>/tmp/mount-err; then
            chown "${CONTAINER_UID}:${CONTAINER_GID}" "$MOUNT_POINT" 2>/dev/null || true
            echo "{\"mounted\": true, \"device\": \"$DEVICE\", \"mount_point\": \"$MOUNT_POINT\", \"message\": \"Mounted (fstype=${FSTYPE:-unknown}), chown attempted\"}"
        else
            ERR=$(cat /tmp/mount-err | tr '"' "'" | tr '\n' ' ')
            echo "{\"mounted\": false, \"device\": \"$DEVICE\", \"mount_point\": \"$MOUNT_POINT\", \"message\": \"$ERR\"}"
            exit 1
        fi
        ;;
esac
