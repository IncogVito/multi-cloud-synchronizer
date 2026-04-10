#!/bin/bash
# DEV MOCK: Returns a fake disk list simulating one external drive (/temp-drive-mock).
# Output: JSON array

MOCK_DIR="/temp-drive-mock"
FREE_BYTES=$(df -B1 --output=avail "$MOCK_DIR" 2>/dev/null | tail -1 | tr -d ' ')
if [ -z "$FREE_BYTES" ]; then FREE_BYTES=10737418240; fi  # 10 GB fallback

SIZE_HUMAN="10G"

echo "[{\"name\": \"mock-drive\", \"path\": \"/dev/mock-drive\", \"size\": \"$SIZE_HUMAN\", \"type\": \"disk\", \"mountpoint\": \"$MOCK_DIR\", \"label\": \"DEV-MOCK\", \"vendor\": \"Mock\", \"model\": \"Temp Drive\"}]"
