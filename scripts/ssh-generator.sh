#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KEY_NAME="${1:-id_rsa}"
REMOTE_USER="${2:-}"
REMOTE_HOST="${3:-}"

KEY_PATH="$SCRIPT_DIR/$KEY_NAME"

if [[ -f "$KEY_PATH" ]]; then
    echo "Key $KEY_PATH already exists. Remove it first or choose a different name."
    exit 1
fi

ssh-keygen -t rsa -b 4096 -f "$KEY_PATH" -N "" -C "${KEY_NAME}@$(hostname)"

echo "Private key: $KEY_PATH"
echo "Public key:  $KEY_PATH.pub"

if [[ -n "$REMOTE_USER" && -n "$REMOTE_HOST" ]]; then
    ssh-copy-id -i "$KEY_PATH.pub" "$REMOTE_USER@$REMOTE_HOST"
    echo "Public key added to $REMOTE_USER@$REMOTE_HOST:~/.ssh/authorized_keys"
else
    echo ""
    echo "To add the public key to a server manually, run:"
    echo "  ssh-copy-id -i $KEY_PATH.pub USER@HOST"
    echo ""
    echo "Or pass user and host as arguments:"
    echo "  $0 $KEY_NAME <user> <host>"
fi
