#!/bin/bash
# DEV MOCK: Returns a fake UUID/label for the mock drive device.
# Usage: ./read-device-id.sh [/dev/sdX]
# Output: JSON {"uuid": str|null, "label": str|null, "device": str}

DEVICE="${1:-/dev/mock-drive}"

echo "{\"uuid\": \"00000000-0000-0000-0000-000000000001\", \"label\": \"DEV-MOCK\", \"device\": \"$DEVICE\"}"
