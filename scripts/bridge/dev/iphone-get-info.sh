#!/bin/bash
# BRIDGE: Delegates to host via SSH.
# Get detailed info about a connected iPhone.
# Usage: ./iphone-get-info.sh [udid]
# Output: JSON with device details

SSH_HOST="${SSH_HOST:-host.docker.internal}"
SSH_USER="${SSH_USER:-}"
SSH_TARGET="${SSH_USER:+${SSH_USER}@}${SSH_HOST}"

exec ssh -o StrictHostKeyChecking=no -o BatchMode=yes "$SSH_TARGET" "/scripts/iphone-get-info.sh" "$@"
