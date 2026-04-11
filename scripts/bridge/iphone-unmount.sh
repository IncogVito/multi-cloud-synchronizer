#!/bin/bash
# BRIDGE: Delegates to host via named pipe.
# Unmount iPhone from IPHONE_MOUNT_PATH.
# Output: JSON {"unmounted": bool, "error": str|null}

# shellcheck source=pipe-call.sh
source "$(dirname "$0")/pipe-call.sh"

args=$(printf '%q ' "$@")
pipe_call "IPHONE_MOUNT_PATH=${IPHONE_MOUNT_PATH:-/mnt/iphone} ${HOST_SCRIPTS_PATH:-/home/wdrozdzowski/projects/multi-cloud-synchronizer/scripts/host}/iphone-unmount.sh $args"
