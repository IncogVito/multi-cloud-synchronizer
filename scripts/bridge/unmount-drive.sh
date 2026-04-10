#!/bin/bash
# BRIDGE: Delegates to host via SSH.
# Unmount the external drive from /mnt/external-drive.
# Output: JSON {"success": bool, "message": str}

SSH_HOST="${SSH_HOST:-host.docker.internal}"
SSH_USER="${SSH_USER:-}"
SSH_TARGET="${SSH_USER:+${SSH_USER}@}${SSH_HOST}"

exec ssh -i /ssh/id_ed25519 -o StrictHostKeyChecking=no -o BatchMode=yes "$SSH_TARGET" "${HOST_SCRIPTS_PATH:-/home/wdrozdzowski/projects/multi-cloud-synchronizer/scripts}/unmount-drive.sh" "$@"
