#!/bin/bash
# Mount the first connected iPhone via ifuse.
# Requires: ifuse, idevice_id (libimobiledevice)
# Output: JSON {"mounted": bool, "mount_path": str|null, "udid": str|null, "error": str|null}

MOUNT_PATH="${IPHONE_MOUNT_PATH:-/mnt/iphone}"

emit() {
    # $1=mounted $2=udid_json $3=error_json
    local mounted="$1" udid_val="$2" error_val="$3"
    local mp="null"
    [ "$mounted" = "true" ] && mp="\"$MOUNT_PATH\""
    printf '{"mounted": %s, "mount_path": %s, "udid": %s, "error": %s}\n' \
        "$mounted" "$mp" "$udid_val" "$error_val"
}

if ! command -v ifuse &>/dev/null; then
    emit false null '"ifuse not installed (apt install ifuse)"'
    exit 1
fi

if ! command -v idevice_id &>/dev/null; then
    emit false null '"idevice_id not found (apt install libimobiledevice-utils)"'
    exit 1
fi

mkdir -p "$MOUNT_PATH"

# Already mounted — idempotent
if mountpoint -q "$MOUNT_PATH" 2>/dev/null; then
    UDID=$(idevice_id -l 2>/dev/null | head -1)
    UDID_JSON="null"
    [ -n "$UDID" ] && UDID_JSON="\"$UDID\""
    emit true "$UDID_JSON" null
    exit 0
fi

UDID=$(idevice_id -l 2>/dev/null | head -1)
if [ -z "$UDID" ]; then
    emit false null '"No iPhone detected (check USB cable and trust prompt)"'
    exit 1
fi

ERR_FILE=$(mktemp)
if ifuse "$MOUNT_PATH" -u "$UDID" 2>"$ERR_FILE"; then
    rm -f "$ERR_FILE"
    emit true "\"$UDID\"" null
else
    ERR=$(sed 's/"/'"'"'/g' "$ERR_FILE" | tr -d '\n')
    rm -f "$ERR_FILE"
    emit false "\"$UDID\"" "\"$ERR\""
    exit 1
fi
