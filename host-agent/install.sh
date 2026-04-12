#!/usr/bin/env bash
# CloudSync Host Agent installer.
# Run as root (or with sudo): sudo ./install.sh
# Idempotent — safe to run again for updates.

set -euo pipefail

INSTALL_DIR="/opt/cloudsync-host-agent"
SERVICE_NAME="cloudsync-host-agent"
SERVICE_FILE="${SERVICE_NAME}.service"
SYSTEMD_DIR="/etc/systemd/system"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
info()    { echo -e "${GREEN}[INFO]${NC}  $*"; }
warn()    { echo -e "${YELLOW}[WARN]${NC}  $*"; }
error()   { echo -e "${RED}[ERROR]${NC} $*" >&2; exit 1; }

# ---- require root ----------------------------------------------------------
[ "$(id -u)" -eq 0 ] || error "This script must be run as root (use sudo)."

# ---- check Python ----------------------------------------------------------
info "Checking Python 3.9+..."
PYTHON=$(command -v python3 || true)
[ -n "$PYTHON" ] || error "python3 not found. Install Python 3.9+ first."
PY_VER=$("$PYTHON" -c "import sys; print(sys.version_info >= (3,9))")
[ "$PY_VER" = "True" ] || error "Python 3.9+ required. Found: $($PYTHON --version)"
info "Found $($PYTHON --version)"

# ---- check optional deps ---------------------------------------------------
for cmd in ifuse idevice_id blkid lsblk; do
    if command -v "$cmd" &>/dev/null; then
        info "Dependency OK: $cmd"
    else
        warn "Optional dependency missing: $cmd  (some actions will fail at runtime)"
    fi
done

# ---- install files ---------------------------------------------------------
info "Installing to $INSTALL_DIR..."
mkdir -p "$INSTALL_DIR/handlers"
cp "$SCRIPT_DIR/agent.py"              "$INSTALL_DIR/"
cp "$SCRIPT_DIR/models.py"             "$INSTALL_DIR/"
cp "$SCRIPT_DIR/handlers/__init__.py"  "$INSTALL_DIR/handlers/"
cp "$SCRIPT_DIR/handlers/drive.py"     "$INSTALL_DIR/handlers/"
cp "$SCRIPT_DIR/handlers/iphone.py"    "$INSTALL_DIR/handlers/"
chmod +x "$INSTALL_DIR/agent.py"
info "Files installed."

# ---- systemd unit ----------------------------------------------------------
info "Installing systemd unit..."
cp "$SCRIPT_DIR/$SERVICE_FILE" "$SYSTEMD_DIR/$SERVICE_FILE"
systemctl daemon-reload
systemctl enable --now "$SERVICE_NAME"
info "Service enabled and started."

# ---- status ----------------------------------------------------------------
echo ""
systemctl status "$SERVICE_NAME" --no-pager || true
echo ""
SOCK_PATH="/run/${SERVICE_NAME}/agent.sock"
info "Installation complete."
info "Socket: $SOCK_PATH"
info "Test:   echo '{\"action\":\"list_disks\",\"params\":{}}' | socat - UNIX-CONNECT:$SOCK_PATH"
info "Logs:   journalctl -u $SERVICE_NAME -f"
