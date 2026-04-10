#!/bin/bash
# DEV MOCK: Simulates checking external drive availability using /temp-drive-mock.
# Usage: ./check-drive.sh [/mnt/external-drive]
# Output: JSON {"available": bool, "path": str|null, "free_bytes": int|null}

MOCK_DIR="/temp-drive-mock"

mkdir -p "$MOCK_DIR"

FREE_BYTES=$(df -B1 --output=avail "$MOCK_DIR" 2>/dev/null | tail -1 | tr -d ' ')
if [ -z "$FREE_BYTES" ]; then FREE_BYTES=null; fi

echo "{\"available\": true, \"path\": \"$MOCK_DIR\", \"free_bytes\": $FREE_BYTES}"
