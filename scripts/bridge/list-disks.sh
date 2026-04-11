#!/bin/bash
# BRIDGE: Delegates to host via named pipe.
# List eligible external block devices for mounting.
# Output: JSON array.

# shellcheck source=pipe-call.sh
source "$(dirname "$0")/pipe-call.sh"

args=$(printf '%q ' "$@")
pipe_call "${HOST_SCRIPTS_PATH:-/home/wdrozdzowski/projects/multi-cloud-synchronizer/scripts/host}/list-disks.sh $args"
