#!/bin/bash
# Check if the connected iPhone has trusted this computer (pairing status).
# Usage: ./iphone-check-trust.sh [udid]
# Output: JSON {"trusted": bool, "udid": str|null, "message": str}

UDID="${1}"

if ! command -v idevicepair &>/dev/null; then
    echo '{"trusted": false, "udid": null, "message": "libimobiledevice not installed"}'
    exit 1
fi

if [ -z "$UDID" ]; then
    UDID=$(idevice_id -l 2>/dev/null | head -1)
fi

if [ -z "$UDID" ]; then
    echo '{"trusted": false, "udid": null, "message": "No iOS device connected"}'
    exit 1
fi

PAIR_STATUS=$(idevicepair -u "$UDID" validate 2>&1)

if echo "$PAIR_STATUS" | grep -q "SUCCESS"; then
    echo "{\"trusted\": true, \"udid\": \"$UDID\", \"message\": \"Device is paired and trusted\"}"
else
    MSG=$(echo "$PAIR_STATUS" | tr '"' "'" | tr '\n' ' ')
    echo "{\"trusted\": false, \"udid\": \"$UDID\", \"message\": \"$MSG\"}"
fi
