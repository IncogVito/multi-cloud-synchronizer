#!/usr/bin/env python3
"""CloudSync Host Agent — asyncio Unix Domain Socket daemon.

Listens on SOCKET_PATH for newline-delimited JSON requests:
    {"action": "...", "params": {...}}

Responds with newline-delimited JSON:
    {"ok": true, "data": {...}}          on success
    {"ok": false, "error": "...", "code": "..."}  on failure
"""
from __future__ import annotations

import asyncio
import dataclasses
import json
import logging
import os
import signal
import sys
from pathlib import Path

# ---- local imports ---------------------------------------------------------
sys.path.insert(0, str(Path(__file__).parent))

import handlers.drive as drive_handlers
import handlers.iphone as iphone_handlers
from handlers.drive import HandlerError as DriveError
from handlers.iphone import HandlerError as IPhoneError

# ---------------------------------------------------------------------------
# Config
# ---------------------------------------------------------------------------

SOCKET_PATH = os.environ.get("SOCKET_PATH", "/run/cloudsync-host-agent/agent.sock")
REQUEST_TIMEOUT = float(os.environ.get("REQUEST_TIMEOUT", "60"))

# ---------------------------------------------------------------------------
# Logging
# ---------------------------------------------------------------------------

LOG_LEVEL = os.environ.get("LOG_LEVEL", "INFO").upper()

logging.basicConfig(
    level=LOG_LEVEL,
    format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    stream=sys.stdout,
)
LOG = logging.getLogger("cloudsync-host-agent")

# ---------------------------------------------------------------------------
# Dispatch table
# ---------------------------------------------------------------------------

DISPATCH: dict[str, callable] = {
    "check_drive":          drive_handlers.check_drive,
    "list_disks":           drive_handlers.list_disks,
    "mount_drive":          drive_handlers.mount_drive,
    "unmount_drive":        drive_handlers.unmount_drive,
    "read_device_id":       drive_handlers.read_device_id,
    "get_disk_details":     drive_handlers.get_disk_details,
    "detect_iphone":        iphone_handlers.detect_iphone,
    "iphone_list_devices":  iphone_handlers.iphone_list_devices,
    "iphone_check_trust":   iphone_handlers.iphone_check_trust,
    "iphone_get_info":      iphone_handlers.iphone_get_info,
    "iphone_mount":         iphone_handlers.iphone_mount,
    "iphone_unmount":       iphone_handlers.iphone_unmount,
}

# ---------------------------------------------------------------------------
# Request handling
# ---------------------------------------------------------------------------

async def handle_connection(reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
    peer = writer.get_extra_info("peername") or "<local>"
    try:
        raw = await asyncio.wait_for(reader.readline(), timeout=REQUEST_TIMEOUT)
        if not raw:
            return
        await _process_request(raw, writer)
    except asyncio.TimeoutError:
        LOG.warning("Request timeout from %s", peer)
        _write_error(writer, "Request timeout", "TIMEOUT")
    except Exception as exc:
        LOG.exception("Unexpected error handling connection from %s", peer)
        _write_error(writer, str(exc), "INTERNAL_ERROR")
    finally:
        try:
            writer.close()
            await writer.wait_closed()
        except Exception:
            pass


async def _process_request(raw: bytes, writer: asyncio.StreamWriter) -> None:
    try:
        request = json.loads(raw.decode())
    except json.JSONDecodeError as exc:
        _write_error(writer, f"Invalid JSON: {exc}", "PARSE_ERROR")
        return

    action = request.get("action")
    params = request.get("params") or {}

    LOG.info("action=%s params=%s", action, params)

    if action not in DISPATCH:
        _write_error(writer, f"Unknown action: {action!r}", "UNKNOWN_ACTION")
        return

    handler = DISPATCH[action]
    loop = asyncio.get_event_loop()
    try:
        result = await loop.run_in_executor(None, handler, params)
    except (DriveError, IPhoneError) as exc:
        _write_error(writer, str(exc), exc.code)
        return
    except Exception as exc:
        LOG.exception("Handler %s raised unexpected exception", action)
        _write_error(writer, str(exc), "HANDLER_EXCEPTION")
        return

    # Serialise dataclass → dict, then to JSON
    if dataclasses.is_dataclass(result) and not isinstance(result, type):
        data = dataclasses.asdict(result)
    elif isinstance(result, list):
        data = [dataclasses.asdict(item) if dataclasses.is_dataclass(item) and not isinstance(item, type) else item
                for item in result]
    else:
        data = result

    _write_ok(writer, data)


def _write_ok(writer: asyncio.StreamWriter, data: object) -> None:
    payload = json.dumps({"ok": True, "data": data}, ensure_ascii=False)
    writer.write((payload + "\n").encode())


def _write_error(writer: asyncio.StreamWriter, error: str, code: str) -> None:
    payload = json.dumps({"ok": False, "error": error, "code": code}, ensure_ascii=False)
    writer.write((payload + "\n").encode())


# ---------------------------------------------------------------------------
# Server lifecycle
# ---------------------------------------------------------------------------

async def main() -> None:
    socket_path = Path(SOCKET_PATH)
    socket_path.parent.mkdir(parents=True, exist_ok=True)

    # Remove stale socket file
    socket_path.unlink(missing_ok=True)

    server = await asyncio.start_unix_server(handle_connection, path=str(socket_path))

    # World-readable socket so the Docker container can connect without root.
    socket_path.chmod(0o666)

    addr = socket_path
    LOG.info("CloudSync Host Agent listening on %s", addr)

    loop = asyncio.get_event_loop()
    for sig in (signal.SIGTERM, signal.SIGINT):
        loop.add_signal_handler(sig, server.close)

    async with server:
        await server.serve_forever()

    LOG.info("Server shut down")


if __name__ == "__main__":
    asyncio.run(main())
