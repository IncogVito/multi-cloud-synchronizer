#!/bin/bash
# BRIDGE: Delegates to host via SSH.
# List all connected iOS devices (UDIDs).
# Output: JSON {"devices": ["udid1", "udid2", ...]}

SSH_HOST="${SSH_HOST:-host.docker.internal}"
SSH_USER="${SSH_USER:-}"
SSH_TARGET="${SSH_USER:+${SSH_USER}@}${SSH_HOST}"

exec ssh -o StrictHostKeyChecking=no -o BatchMode=yes "$SSH_TARGET" "/scripts/iphone-list-devices.sh" "$@"
