#!/bin/bash
# BRIDGE: Delegates to host via named pipe.
# Mount iPhone via ifuse.
# Output: JSON {"mounted": bool, "mount_path": str|null, "udid": str|null, "error": str|null}

# shellcheck source=pipe-call.sh
source "$(dirname "$0")/pipe-call.sh"

args=$(printf '%q ' "$@")
pipe_call "IPHONE_MOUNT_PATH=${IPHONE_MOUNT_PATH:-/mnt/iphone} ${HOST_SCRIPTS_PATH:-/home/wdrozdzowski/projects/multi-cloud-synchronizer/scripts/host}/iphone-mount.sh $args"
