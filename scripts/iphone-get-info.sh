#!/bin/bash
# Get detailed info about a connected iPhone.
# Usage: ./iphone-get-info.sh [udid]   (omit udid to use first found device)
# Output: JSON with device details

UDID="${1}"

if ! command -v ideviceinfo &>/dev/null; then
    echo '{"error": "libimobiledevice not installed"}'
    exit 1
fi

if [ -z "$UDID" ]; then
    UDID=$(idevice_id -l 2>/dev/null | head -1)
fi

if [ -z "$UDID" ]; then
    echo '{"error": "No iOS device found"}'
    exit 1
fi

get_info() {
    ideviceinfo -u "$UDID" -k "$1" 2>/dev/null || echo ""
}

DEVICE_NAME=$(get_info DeviceName)
PRODUCT_TYPE=$(get_info ProductType)
PRODUCT_VERSION=$(get_info ProductVersion)
SERIAL=$(get_info SerialNumber)
CAPACITY=$(get_info TotalDiskCapacity)
FREE=$(get_info TotalDataAvailable)
BATTERY=$(get_info BatteryCurrentCapacity)

# Escape double quotes in strings
esc() { echo "$1" | sed 's/"/\\"/g'; }

cat <<EOF
{
  "udid": "$(esc "$UDID")",
  "device_name": "$(esc "$DEVICE_NAME")",
  "product_type": "$(esc "$PRODUCT_TYPE")",
  "ios_version": "$(esc "$PRODUCT_VERSION")",
  "serial_number": "$(esc "$SERIAL")",
  "total_capacity_bytes": ${CAPACITY:-null},
  "free_bytes": ${FREE:-null},
  "battery_percent": ${BATTERY:-null}
}
EOF
