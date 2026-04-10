#!/bin/bash
# BRIDGE: Delegates to host via SSH.
# List eligible external block devices for mounting.
# Output: JSON array.

SSH_HOST="${SSH_HOST:-host.docker.internal}"
SSH_USER="${SSH_USER:-}"
SSH_TARGET="${SSH_USER:+${SSH_USER}@}${SSH_HOST}"

exec ssh -o StrictHostKeyChecking=no -o BatchMode=yes "$SSH_TARGET" "/scripts/list-disks.sh" "$@"
