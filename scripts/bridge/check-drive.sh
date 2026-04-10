#!/bin/bash
# BRIDGE: Delegates to host via SSH.
# Check if the external drive is available at a given path.
# Usage: ./check-drive.sh [/mnt/external-drive]
# Output: JSON {"available": bool, "path": str|null, "free_bytes": int|null}

SSH_HOST="${SSH_HOST:-host.docker.internal}"
SSH_USER="${SSH_USER:-}"
SSH_TARGET="${SSH_USER:+${SSH_USER}@}${SSH_HOST}"

exec ssh -o StrictHostKeyChecking=no -o BatchMode=yes "$SSH_TARGET" "/scripts/check-drive.sh" "$@"
