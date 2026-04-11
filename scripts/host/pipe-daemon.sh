#!/bin/bash
# Named pipe bridge daemon — runs on the HOST.
# The backend container writes commands to the FIFO; this daemon executes them
# and writes the response (exit code + stdout/stderr) to a per-request temp file
# in $RESP_DIR that the container polls for.
#
# HOST SETUP — run once:
#   1. Create the pipe directory and start the daemon:
#        sudo mkdir -p /var/run/cloudsync-bridge
#        sudo chmod 777 /var/run/cloudsync-bridge
#        nohup /path/to/scripts/host/pipe-daemon.sh &>/var/log/cloudsync-bridge.log &
#
#   2. Persist across reboots via crontab:
#        crontab -e
#        # Add the line below (adjust the path):
#        @reboot /home/wdrozdzowski/projects/multi-cloud-synchronizer/scripts/host/pipe-daemon.sh >> /var/log/cloudsync-bridge.log 2>&1
#
#   3. Mount PIPE_DIR into the backend container (already done in compose files):
#        - /var/run/cloudsync-bridge:/bridge   (in volumes section)
#
# PROTOCOL:
#   Container writes: "<uuid> <shell command with quoted args>\n"
#   Daemon executes the command via eval and writes to $RESP_DIR/<uuid>:
#     Line 1: exit code
#     Line 2+: stdout+stderr of the command
#   Container polls until $RESP_DIR/<uuid> appears, reads it, then removes it.

set -euo pipefail

PIPE_DIR="${PIPE_DIR:-$HOME/cloudsync-bridge}"
CMD_PIPE="$PIPE_DIR/cmd"
RESP_DIR="$PIPE_DIR/responses"
LOG_FILE="${LOG_FILE:-$PIPE_DIR/daemon.log}"

# Whitelist: only these script basenames may be executed via the bridge.
ALLOWED_SCRIPTS=(
    "check-drive.sh"
    "detect-iphone.sh"
    "iphone-check-trust.sh"
    "iphone-get-info.sh"
    "iphone-list-devices.sh"
    "iphone-mount.sh"
    "iphone-unmount.sh"
    "list-disks.sh"
    "mount-drive.sh"
    "read-device-id.sh"
    "unmount-drive.sh"
)

# Returns 0 if the command is allowed, 1 otherwise.
# Allowed format (same as bridge scripts produce):
#   [KEY=VALUE ]* /absolute/path/to/allowed-script.sh [args...]
# Rules:
#   1. Env var keys must match ^[A-Z_][A-Z0-9_]*$ and values must not contain shell metacharacters.
#   2. The script path must be under HOST_SCRIPTS_PATH.
#   3. The script basename must be in ALLOWED_SCRIPTS.
#   4. No shell metacharacters (; | & ` $ ( ) < > \n) outside of quoted env values.
is_command_allowed() {
    local cmd="$1"
    local host_scripts_path="${HOST_SCRIPTS_PATH:-}"

    # Reject if any shell metacharacter appears anywhere in the command.
    # Env values with = are allowed to contain / _ - . but nothing dangerous.
    local bad_chars='[;&|`$()<>\\]'
    if [[ "$cmd" =~ $bad_chars ]]; then
        return 1
    fi

    # Walk through space-separated tokens; skip leading KEY=VALUE pairs.
    local script_path=""
    for token in $cmd; do
        if [[ "$token" =~ ^[A-Z_][A-Z0-9_]*=.* ]]; then
            continue  # env var prefix — skip
        fi
        script_path="$token"
        break
    done

    [[ -z "$script_path" ]] && return 1

    # Script must be under HOST_SCRIPTS_PATH (if set).
    if [[ -n "$host_scripts_path" && "$script_path" != "$host_scripts_path"/* ]]; then
        return 1
    fi

    # Script basename must be in the whitelist.
    local basename
    basename="$(basename "$script_path")"
    for allowed in "${ALLOWED_SCRIPTS[@]}"; do
        [[ "$basename" == "$allowed" ]] && return 0
    done
    return 1
}

mkdir -p "$RESP_DIR"
chmod 0777 "$RESP_DIR"

# (Re)create the command FIFO
rm -f "$CMD_PIPE"
mkfifo "$CMD_PIPE"
chmod 0666 "$CMD_PIPE"

log() {
    printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" | tee -a "$LOG_FILE"
}

log "pipe-daemon started (PID $$). Listening on $CMD_PIPE"

# Open fd 3 in read+write mode so the FIFO never produces EOF between requests
# (a plain 'read < fifo' loop would exit as soon as all writers close their end).
exec 3<>"$CMD_PIPE"

while IFS= read -r line <&3; do
    [ -z "$line" ] && continue

    req_id="${line%% *}"
    command="${line#* }"
    resp_file="$RESP_DIR/$req_id"

    log "REQ $req_id: $command"

    # Run the command asynchronously so the daemon stays responsive.
    (
        if ! is_command_allowed "$command"; then
            log "DENIED $req_id: $command"
            printf '%d\n%s' "1" "ERROR: command not in whitelist: $command" > "${resp_file}.tmp"
            mv "${resp_file}.tmp" "$resp_file"
        else
            output=$(eval "$command" 2>&1)
            code=$?
            log "RSP $req_id: exit=$code"
            # Write atomically: temp file then rename so the container never reads a partial response.
            printf '%d\n%s' "$code" "$output" > "${resp_file}.tmp"
            mv "${resp_file}.tmp" "$resp_file"
        fi
    ) &
done

log "pipe-daemon exiting (fd 3 closed — this should not happen)"
