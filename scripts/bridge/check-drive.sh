#!/bin/bash
# BRIDGE: Delegates to host via named pipe.
# Check if the external drive is available at a given path.
# Usage: ./check-drive.sh [/mnt/external-drive]
# Output: JSON {"available": bool, "path": str|null, "free_bytes": int|null}

# shellcheck source=pipe-call.sh
source "$(dirname "$0")/pipe-call.sh"

args=$(printf '%q ' "$@")
pipe_call "${HOST_SCRIPTS_PATH:-/home/wdrozdzowski/projects/multi-cloud-synchronizer/scripts/host}/check-drive.sh $args"
