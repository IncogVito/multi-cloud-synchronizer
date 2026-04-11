#!/bin/bash
# Shared helper sourced by bridge scripts.
# Sends a command to the host pipe-daemon and returns its stdout + exit code.
#
# Usage (from a bridge script):
#   source "$(dirname "$0")/pipe-call.sh"
#   pipe_call "SOME_ENV=value /path/to/host-script.sh arg1 arg2"
#
# Environment:
#   BRIDGE_PIPE_DIR  — mount point of the shared pipe dir inside the container (default: /bridge)
#   BRIDGE_TIMEOUT   — seconds to wait for a response before giving up (default: 30)

BRIDGE_PIPE_DIR="${BRIDGE_PIPE_DIR:-/bridge}"
_PIPE_CMD="$BRIDGE_PIPE_DIR/cmd"
_PIPE_RESP="$BRIDGE_PIPE_DIR/responses"

pipe_call() {
    local command="$1"

    if [ ! -p "$_PIPE_CMD" ]; then
        printf '{"error":"bridge FIFO not found at %s — is pipe-daemon running on the host?"}\n' "$_PIPE_CMD" >&2
        exit 1
    fi

    local req_id
    req_id="$(cat /proc/sys/kernel/random/uuid)"
    local resp_file="$_PIPE_RESP/$req_id"

    # Send: "<uuid> <command>\n"
    printf '%s %s\n' "$req_id" "$command" > "$_PIPE_CMD"

    # Poll for the response file; the daemon writes it atomically via mv.
    local timeout="${BRIDGE_TIMEOUT:-30}"
    local ticks=0
    local max_ticks=$(( timeout * 10 ))

    while [ "$ticks" -lt "$max_ticks" ]; do
        if [ -f "$resp_file" ]; then
            local exit_code
            exit_code=$(head -1 "$resp_file")
            tail -n +2 "$resp_file"
            rm -f "$resp_file"
            return "$exit_code"
        fi
        sleep 0.1
        ticks=$(( ticks + 1 ))
    done

    printf '{"error":"bridge timeout after %ds (req=%s)"}\n' "$timeout" "$req_id" >&2
    exit 124
}
