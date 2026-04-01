#!/bin/bash
# Detect if an iPhone is connected via USB.
# Output: JSON {"connected": bool, "device_name": str|null, "udid": str|null}
#
# Uses libimobiledevice (idevice_id, ideviceinfo) if available,
# falls back to USB scanning via lsusb.

DEVICE_NAME=null
UDID=null
CONNECTED=false

# --- Strategy 1: libimobiledevice ---
if command -v idevice_id &>/dev/null; then
    UDID_RAW=$(idevice_id -l 2>/dev/null | head -1)
    if [ -n "$UDID_RAW" ]; then
        CONNECTED=true
        UDID="\"$UDID_RAW\""
        NAME_RAW=$(ideviceinfo -u "$UDID_RAW" -k DeviceName 2>/dev/null)
        if [ -n "$NAME_RAW" ]; then
            DEVICE_NAME="\"$NAME_RAW\""
        fi
    fi
# --- Strategy 2: lsusb fallback (Apple Vendor ID 05ac) ---
elif command -v lsusb &>/dev/null; then
    APPLE_USB=$(lsusb 2>/dev/null | grep -i "05ac:" | grep -iE "iphone|ipad|apple mobile" | head -1)
    if [ -n "$APPLE_USB" ]; then
        CONNECTED=true
        DEVICE_NAME='"Apple iOS Device"'
    fi
fi

echo "{\"connected\": $CONNECTED, \"device_name\": $DEVICE_NAME, \"udid\": $UDID}"
