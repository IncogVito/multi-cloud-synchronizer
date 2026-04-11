#!/bin/bash
# BRIDGE: Delegates to host via named pipe.
# Check if the connected iPhone has trusted this computer (pairing status).
# Usage: ./iphone-check-trust.sh [udid]
# Output: JSON {"trusted": bool, "udid": str|null, "message": str}

# shellcheck source=pipe-call.sh
source "$(dirname "$0")/pipe-call.sh"

args=$(printf '%q ' "$@")
pipe_call "${HOST_SCRIPTS_PATH:-/home/wdrozdzowski/projects/multi-cloud-synchronizer/scripts/host}/iphone-check-trust.sh $args"
