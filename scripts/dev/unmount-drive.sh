#!/bin/bash
# DEV MOCK: Simulates unmounting the external drive (no-op, /temp-drive-mock stays).
# Output: JSON {"success": bool, "message": str}

MOCK_DIR="/temp-drive-mock"

echo "{\"success\": true, \"message\": \"[DEV] Mocked unmount: $MOCK_DIR left intact (dev mode)\"}"
