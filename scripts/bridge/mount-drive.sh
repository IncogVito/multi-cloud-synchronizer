#!/bin/bash
# BRIDGE: Delegates to host via named pipe.
# Mount a block device at a given mount point.
# Usage: ./mount-drive.sh /dev/sdX [/mnt/external-drive]
# Output: JSON {"mounted": bool, "device": str, "mount_point": str, "message": str}

# shellcheck source=pipe-call.sh
source "$(dirname "$0")/pipe-call.sh"

args=$(printf '%q ' "$@")
pipe_call "${HOST_SCRIPTS_PATH:-/home/wdrozdzowski/projects/multi-cloud-synchronizer/scripts/host}/mount-drive.sh $args"
