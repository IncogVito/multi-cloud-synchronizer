#!/bin/bash
# BRIDGE: Delegates to host via named pipe.
# Get detailed info about a connected iPhone.
# Usage: ./iphone-get-info.sh [udid]
# Output: JSON with device details

# shellcheck source=pipe-call.sh
source "$(dirname "$0")/pipe-call.sh"

args=$(printf '%q ' "$@")
pipe_call "${HOST_SCRIPTS_PATH:-/home/wdrozdzowski/projects/multi-cloud-synchronizer/scripts/host}/iphone-get-info.sh $args"
