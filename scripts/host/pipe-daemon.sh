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

PIPE_DIR="${PIPE_DIR:-/var/run/cloudsync-bridge}"
CMD_PIPE="$PIPE_DIR/cmd"
RESP_DIR="$PIPE_DIR/responses"
LOG_FILE="${LOG_FILE:-$PIPE_DIR/daemon.log}"

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
        output=$(eval "$command" 2>&1)
        code=$?
        log "RSP $req_id: exit=$code"
        # Write atomically: temp file then rename so the container never reads a partial response.
        printf '%d\n%s' "$code" "$output" > "${resp_file}.tmp"
        mv "${resp_file}.tmp" "$resp_file"
    ) &
done

log "pipe-daemon exiting (fd 3 closed — this should not happen)"
