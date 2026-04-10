#!/bin/bash
# BRIDGE: Delegates to host via SSH.
# Mount iPhone via ifuse.
# Output: JSON {"mounted": bool, "mount_path": str|null, "udid": str|null, "error": str|null}

SSH_HOST="${SSH_HOST:-host.docker.internal}"
SSH_USER="${SSH_USER:-}"
SSH_TARGET="${SSH_USER:+${SSH_USER}@}${SSH_HOST}"

exec ssh -i /ssh/id_ed25519 -o StrictHostKeyChecking=no -o BatchMode=yes "$SSH_TARGET" \
    "IPHONE_MOUNT_PATH=${IPHONE_MOUNT_PATH:-/mnt/iphone} ${HOST_SCRIPTS_PATH:-/home/wdrozdzowski/projects/multi-cloud-synchronizer/scripts/host}/iphone-mount.sh" "$@"
