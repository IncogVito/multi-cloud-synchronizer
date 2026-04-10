#!/bin/bash
# BRIDGE: Delegates to host via SSH.
# List eligible external block devices for mounting.
# Output: JSON array.

SSH_HOST="${SSH_HOST:-host.docker.internal}"
SSH_USER="${SSH_USER:-}"
SSH_TARGET="${SSH_USER:+${SSH_USER}@}${SSH_HOST}"

exec ssh -i /ssh/id_ed25519 -o StrictHostKeyChecking=no -o BatchMode=yes "$SSH_TARGET" "${HOST_SCRIPTS_PATH:-/home/wdrozdzowski/projects/multi-cloud-synchronizer/scripts}/list-disks.sh" "$@"
