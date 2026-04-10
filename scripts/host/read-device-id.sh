#!/bin/bash
# Read UUID and label from a block device using blkid.
# Usage: ./read-device-id.sh /dev/sdb1
# Output: JSON {"uuid": str|null, "label": str|null, "device": str}

DEVICE="${1}"

if [ -z "$DEVICE" ]; then
    echo "{\"uuid\": null, \"label\": null, \"device\": null, \"error\": \"No device specified\"}"
    exit 1
fi

UUID=$(blkid -s UUID -o value "$DEVICE" 2>/dev/null)
LABEL=$(blkid -s LABEL -o value "$DEVICE" 2>/dev/null)

if [ -z "$UUID" ]; then
    echo "{\"uuid\": null, \"label\": null, \"device\": \"$DEVICE\", \"error\": \"Could not read UUID\"}"
    exit 1
fi

UUID_JSON="\"$UUID\""
LABEL_JSON=$([ -n "$LABEL" ] && echo "\"$LABEL\"" || echo "null")

echo "{\"uuid\": $UUID_JSON, \"label\": $LABEL_JSON, \"device\": \"$DEVICE\"}"
