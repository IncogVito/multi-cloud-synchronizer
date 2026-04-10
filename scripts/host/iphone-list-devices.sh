#!/bin/bash
# List all connected iOS devices (UDIDs).
# Output: JSON {"devices": ["udid1", "udid2", ...]}

if ! command -v idevice_id &>/dev/null; then
    echo '{"devices": [], "error": "libimobiledevice not installed"}'
    exit 1
fi

UDIDS=$(idevice_id -l 2>/dev/null)
if [ -z "$UDIDS" ]; then
    echo '{"devices": []}'
    exit 0
fi

# Build JSON array
JSON_ARRAY=$(echo "$UDIDS" | awk 'BEGIN{printf "["} NR>1{printf ","} {printf "\"%s\"", $1} END{printf "]"}')
echo "{\"devices\": $JSON_ARRAY}"
