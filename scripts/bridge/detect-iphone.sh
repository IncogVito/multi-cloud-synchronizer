#!/bin/bash
# BRIDGE: Delegates to host via named pipe.
# Detect if an iPhone is connected via USB.
# Output: JSON {"connected": bool, "device_name": str|null, "udid": str|null}

# shellcheck source=pipe-call.sh
source "$(dirname "$0")/pipe-call.sh"

args=$(printf '%q ' "$@")
pipe_call "${HOST_SCRIPTS_PATH:-/home/wdrozdzowski/projects/multi-cloud-synchronizer/scripts/host}/detect-iphone.sh $args"
