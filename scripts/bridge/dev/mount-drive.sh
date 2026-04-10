#!/bin/bash
# DEV MOCK: Simulates mounting an external drive using /temp-drive-mock directory.
# Usage: ./mount-drive.sh [/dev/sdX] [/mnt/external-drive]
# Output: JSON {"mounted": bool, "device": str, "mount_point": str, "message": str}

DEVICE="${1:-/dev/mock-drive}"
MOUNT_POINT="${2:-/mnt/external-drive}"
MOCK_DIR="/temp-drive-mock"

mkdir -p "$MOCK_DIR"

echo "{\"mounted\": true, \"device\": \"$DEVICE\", \"mount_point\": \"$MOCK_DIR\", \"message\": \"[DEV] Mocked mount: using $MOCK_DIR as external drive\"}"
