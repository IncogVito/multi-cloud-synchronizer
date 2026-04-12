"""iPhone-related host agent handlers using libimobiledevice + ifuse."""
from __future__ import annotations

import logging
import os
import subprocess
from pathlib import Path

from models import (
    IPhoneDetectResult,
    IPhoneInfoResult,
    IPhoneListDevicesResult,
    IPhoneMountResult,
    IPhoneTrustResult,
    IPhoneUnmountResult,
)

LOG = logging.getLogger(__name__)

DEFAULT_MOUNT_PATH = os.environ.get("IPHONE_MOUNT_PATH", "/mnt/iphone")


class HandlerError(Exception):
    def __init__(self, message: str, code: str = "HANDLER_ERROR"):
        super().__init__(message)
        self.code = code


def _run(cmd: list[str], timeout: int = 10) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)


def _cmd_exists(name: str) -> bool:
    return subprocess.run(["which", name], capture_output=True).returncode == 0


# ---------------------------------------------------------------------------
# detect_iphone
# ---------------------------------------------------------------------------

def detect_iphone(params: dict) -> IPhoneDetectResult:  # noqa: ARG001
    if _cmd_exists("idevice_id"):
        udid = _first_udid()
        if udid:
            device_name = _get_device_name(udid)
            return IPhoneDetectResult(connected=True, device_name=device_name, udid=udid)
        return IPhoneDetectResult(connected=False, device_name=None, udid=None)

    # Fallback: lsusb
    if _cmd_exists("lsusb"):
        r = _run(["lsusb"])
        lines = r.stdout.splitlines()
        for line in lines:
            lower = line.lower()
            if "05ac:" in lower and any(k in lower for k in ("iphone", "ipad", "apple mobile")):
                return IPhoneDetectResult(connected=True, device_name="Apple iOS Device", udid=None)

    return IPhoneDetectResult(connected=False, device_name=None, udid=None)


# ---------------------------------------------------------------------------
# iphone_list_devices
# ---------------------------------------------------------------------------

def iphone_list_devices(params: dict) -> IPhoneListDevicesResult:  # noqa: ARG001
    if not _cmd_exists("idevice_id"):
        raise HandlerError("libimobiledevice not installed", "MISSING_DEPENDENCY")

    r = _run(["idevice_id", "-l"])
    devices = [line.strip() for line in r.stdout.splitlines() if line.strip()]
    return IPhoneListDevicesResult(devices=devices)


# ---------------------------------------------------------------------------
# iphone_check_trust
# ---------------------------------------------------------------------------

def iphone_check_trust(params: dict) -> IPhoneTrustResult:
    if not _cmd_exists("idevicepair"):
        raise HandlerError("libimobiledevice not installed", "MISSING_DEPENDENCY")

    udid = params.get("udid") or _first_udid()
    if not udid:
        raise HandlerError("No iOS device connected", "NO_DEVICE")

    r = _run(["idevicepair", "-u", udid, "validate"])
    if "SUCCESS" in r.stdout or "SUCCESS" in r.stderr:
        return IPhoneTrustResult(trusted=True, udid=udid, message="Device is paired and trusted")

    msg = (r.stdout + " " + r.stderr).strip()
    return IPhoneTrustResult(trusted=False, udid=udid, message=msg)


# ---------------------------------------------------------------------------
# iphone_get_info
# ---------------------------------------------------------------------------

def iphone_get_info(params: dict) -> IPhoneInfoResult:
    if not _cmd_exists("ideviceinfo"):
        raise HandlerError("libimobiledevice not installed", "MISSING_DEPENDENCY")

    udid = params.get("udid") or _first_udid()
    if not udid:
        raise HandlerError("No iOS device found", "NO_DEVICE")

    def get_key(key: str) -> str:
        r = _run(["ideviceinfo", "-u", udid, "-k", key])
        return r.stdout.strip()

    total_str = get_key("TotalDiskCapacity")
    free_str = get_key("TotalDataAvailable")
    battery_str = get_key("BatteryCurrentCapacity")

    return IPhoneInfoResult(
        udid=udid,
        device_name=get_key("DeviceName"),
        product_type=get_key("ProductType"),
        ios_version=get_key("ProductVersion"),
        serial_number=get_key("SerialNumber"),
        total_capacity_bytes=_parse_int(total_str),
        free_bytes=_parse_int(free_str),
        battery_percent=_parse_int(battery_str),
    )


# ---------------------------------------------------------------------------
# iphone_mount
# ---------------------------------------------------------------------------

def iphone_mount(params: dict) -> IPhoneMountResult:
    mount_path = params.get("mount_path", DEFAULT_MOUNT_PATH)

    if not _cmd_exists("ifuse"):
        raise HandlerError("ifuse not installed (apt install ifuse)", "MISSING_DEPENDENCY")
    if not _cmd_exists("idevice_id"):
        raise HandlerError("idevice_id not found (apt install libimobiledevice-utils)", "MISSING_DEPENDENCY")

    Path(mount_path).mkdir(parents=True, exist_ok=True)

    # Already mounted — idempotent
    if _is_mountpoint(mount_path):
        udid = _first_udid()
        return IPhoneMountResult(mounted=True, mount_path=mount_path, udid=udid, error=None)

    udid = _first_udid()
    if not udid:
        raise HandlerError("No iPhone detected (check USB cable and trust prompt)", "NO_DEVICE")

    r = _run(["ifuse", mount_path, "-u", udid], timeout=30)
    if r.returncode == 0:
        return IPhoneMountResult(mounted=True, mount_path=mount_path, udid=udid, error=None)

    err = (r.stderr + " " + r.stdout).strip()
    return IPhoneMountResult(mounted=False, mount_path=None, udid=udid, error=err)


# ---------------------------------------------------------------------------
# iphone_unmount
# ---------------------------------------------------------------------------

def iphone_unmount(params: dict) -> IPhoneUnmountResult:
    mount_path = params.get("mount_path", DEFAULT_MOUNT_PATH)

    # Not mounted — idempotent
    if not _is_mountpoint(mount_path):
        return IPhoneUnmountResult(unmounted=True, error=None)

    # Try fusermount first, fall back to umount
    for cmd in [["fusermount", "-u", mount_path], ["umount", mount_path]]:
        r = _run(cmd, timeout=15)
        if r.returncode == 0:
            return IPhoneUnmountResult(unmounted=True, error=None)

    err = r.stderr.strip() or r.stdout.strip()
    return IPhoneUnmountResult(unmounted=False, error=err)


# ---------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------

def _first_udid() -> str | None:
    try:
        r = _run(["idevice_id", "-l"])
        lines = [l.strip() for l in r.stdout.splitlines() if l.strip()]
        return lines[0] if lines else None
    except Exception:
        return None


def _get_device_name(udid: str) -> str | None:
    try:
        r = _run(["ideviceinfo", "-u", udid, "-k", "DeviceName"])
        name = r.stdout.strip()
        return name or None
    except Exception:
        return None


def _is_mountpoint(path: str) -> bool:
    try:
        r = subprocess.run(["mountpoint", "-q", path], timeout=5)
        return r.returncode == 0
    except Exception:
        return False


def _parse_int(s: str) -> int | None:
    try:
        return int(s)
    except (ValueError, TypeError):
        return None
