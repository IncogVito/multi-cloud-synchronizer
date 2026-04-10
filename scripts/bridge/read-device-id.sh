#!/bin/bash
# BRIDGE: Delegates to host via SSH.
# Read UUID and label from a block device using blkid.
# Usage: ./read-device-id.sh /dev/sdb1
# Output: JSON {"uuid": str|null, "label": str|null, "device": str}

SSH_HOST="${SSH_HOST:-host.docker.internal}"
SSH_USER="${SSH_USER:-}"
SSH_TARGET="${SSH_USER:+${SSH_USER}@}${SSH_HOST}"

exec ssh -o StrictHostKeyChecking=no -o BatchMode=yes "$SSH_TARGET" "/scripts/read-device-id.sh" "$@"
