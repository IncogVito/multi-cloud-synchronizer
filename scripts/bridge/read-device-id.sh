#!/bin/bash
# BRIDGE: Delegates to host via named pipe.
# Read UUID and label from a block device using blkid.
# Usage: ./read-device-id.sh /dev/sdb1
# Output: JSON {"uuid": str|null, "label": str|null, "device": str}

# shellcheck source=pipe-call.sh
source "$(dirname "$0")/pipe-call.sh"

args=$(printf '%q ' "$@")
pipe_call "${HOST_SCRIPTS_PATH:-/home/wdrozdzowski/projects/multi-cloud-synchronizer/scripts/host}/read-device-id.sh $args"
