#!/bin/bash
# BRIDGE: Delegates to host via named pipe.
# List all connected iOS devices (UDIDs).
# Output: JSON {"devices": ["udid1", "udid2", ...]}

# shellcheck source=pipe-call.sh
source "$(dirname "$0")/pipe-call.sh"

args=$(printf '%q ' "$@")
pipe_call "${HOST_SCRIPTS_PATH:-/home/wdrozdzowski/projects/multi-cloud-synchronizer/scripts/host}/iphone-list-devices.sh $args"
