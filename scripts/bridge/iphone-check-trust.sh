#!/bin/bash
# BRIDGE: Delegates to host via SSH.
# Check if the connected iPhone has trusted this computer (pairing status).
# Usage: ./iphone-check-trust.sh [udid]
# Output: JSON {"trusted": bool, "udid": str|null, "message": str}

SSH_HOST="${SSH_HOST:-host.docker.internal}"
SSH_USER="${SSH_USER:-}"
SSH_TARGET="${SSH_USER:+${SSH_USER}@}${SSH_HOST}"

exec ssh -i /ssh/id_ed25519 -o StrictHostKeyChecking=no -o BatchMode=yes "$SSH_TARGET" "${HOST_SCRIPTS_PATH:-/home/wdrozdzowski/projects/multi-cloud-synchronizer/scripts}/iphone-check-trust.sh" "$@"
