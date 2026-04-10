#!/bin/bash
# BRIDGE: Delegates to host via SSH.
# Mount a block device at a given mount point.
# Usage: ./mount-drive.sh /dev/sdX [/mnt/external-drive]
# Output: JSON {"mounted": bool, "device": str, "mount_point": str, "message": str}

SSH_HOST="${SSH_HOST:-host.docker.internal}"
SSH_USER="${SSH_USER:-}"
SSH_TARGET="${SSH_USER:+${SSH_USER}@}${SSH_HOST}"

exec ssh -i /ssh/id_ed25519 -o StrictHostKeyChecking=no -o BatchMode=yes "$SSH_TARGET" "${HOST_SCRIPTS_PATH:-/home/wdrozdzowski/projects/multi-cloud-synchronizer/scripts}/mount-drive.sh" "$@"
