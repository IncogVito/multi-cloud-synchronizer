#!/bin/bash
# BRIDGE: Delegates to host via named pipe.
# Unmount the external drive from /mnt/external-drive.
# Output: JSON {"success": bool, "message": str}

# shellcheck source=pipe-call.sh
source "$(dirname "$0")/pipe-call.sh"

args=$(printf '%q ' "$@")
pipe_call "${HOST_SCRIPTS_PATH:-/home/wdrozdzowski/projects/multi-cloud-synchronizer/scripts/host}/unmount-drive.sh $args"
