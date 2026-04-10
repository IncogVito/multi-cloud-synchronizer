#!/bin/bash
# BRIDGE: Delegates to host via SSH.
# Detect if an iPhone is connected via USB.
# Output: JSON {"connected": bool, "device_name": str|null, "udid": str|null}

SSH_HOST="${SSH_HOST:-host.docker.internal}"
SSH_USER="${SSH_USER:-}"
SSH_TARGET="${SSH_USER:+${SSH_USER}@}${SSH_HOST}"

exec ssh -i /ssh/id_ed25519 -o StrictHostKeyChecking=no -o BatchMode=yes "$SSH_TARGET" "${HOST_SCRIPTS_PATH:-/home/wdrozdzowski/projects/multi-cloud-synchronizer/scripts}/detect-iphone.sh" "$@"
