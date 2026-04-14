"""Drive-related host agent handlers.

Each public function corresponds to one JSON action. Functions receive the
`params` dict from the request and return a dataclass instance (or raise
HandlerError on expected failures).
"""
from __future__ import annotations

import json
import logging
import os
import shutil
import subprocess
from pathlib import Path

from models import (
    DeviceIdResult,
    DiskInfo,
    DriveStatus,
    MountDriveResult,
    UnmountDriveResult,
)

LOG = logging.getLogger(__name__)

# UID/GID of the container user (gradle/backend) – used for chown on ext4 mounts.
CONTAINER_UID = int(os.environ.get("CONTAINER_UID", "1000"))
CONTAINER_GID = int(os.environ.get("CONTAINER_GID", "1000"))

DEFAULT_MOUNT_POINT = os.environ.get("EXTERNAL_DRIVE_PATH")

# When DEV_MOCK=true the agent skips real mount/blkid/lsblk calls and returns
# plausible fake responses so the stack can run without a physical drive.
DEV_MOCK = os.environ.get("DEV_MOCK", "").lower() in ("1", "true", "yes")


class HandlerError(Exception):
    """Expected operational failure – will be returned as {"ok": false, ...}."""

    def __init__(self, message: str, code: str = "HANDLER_ERROR"):
        super().__init__(message)
        self.code = code


# ---------------------------------------------------------------------------
# check_drive
# ---------------------------------------------------------------------------

def check_drive(params: dict) -> DriveStatus:
    mount_path = params.get("mount_path") or DEFAULT_MOUNT_POINT
    if not mount_path:
        raise HandlerError("No mount_path specified and EXTERNAL_DRIVE_PATH not set", "MISSING_PARAM")
    p = Path(mount_path)

    if DEV_MOCK:
        if not p.is_dir():
            return DriveStatus(available=False, path=None, free_bytes=None)
        usage = _get_disk_usage(mount_path)
        return DriveStatus(
            available=True,
            path=mount_path,
            free_bytes=usage.free if usage else 10 * 1024 ** 3,
            total_bytes=usage.total if usage else 50 * 1024 ** 3,
        )

    if not p.is_dir() or not _is_mountpoint(mount_path):
        return DriveStatus(available=False, path=None, free_bytes=None)

    usage = _get_disk_usage(mount_path)
    free_bytes = usage.free if usage else None
    total_bytes = usage.total if usage else None
    return DriveStatus(available=True, path=mount_path, free_bytes=free_bytes, total_bytes=total_bytes)


# ---------------------------------------------------------------------------
# list_disks
# ---------------------------------------------------------------------------

_SYSTEM_MOUNTS = frozenset(("[SWAP]", "/", "/boot"))


def list_disks(params: dict) -> list[DiskInfo]:  # noqa: ARG001
    if DEV_MOCK:
        return [DiskInfo(
            name="sdb",
            path="/dev/sdb",
            size="50G",
            type="disk",
            mountpoint=DEFAULT_MOUNT_POINT,
            label="DEV-MOCK-DRIVE",
            vendor="Mock",
            model="Virtual External Drive",
        )]

    try:
        result = subprocess.run(
            ["lsblk", "-J", "-o", "NAME,SIZE,TYPE,MOUNTPOINT,LABEL,VENDOR,MODEL"],
            capture_output=True, text=True, timeout=10
        )
        data = json.loads(result.stdout)
    except Exception as exc:
        raise HandlerError(f"lsblk failed: {exc}", "LSBLK_FAILED") from exc

    return _parse_lsblk(data)


def _is_system_mount(mp: str | None) -> bool:
    if not mp:
        return False
    if mp in _SYSTEM_MOUNTS:
        return True
    if mp.startswith("/mnt/wslg") or mp.startswith("/boot"):
        return True
    return False


def _has_system_mount_recursive(dev: dict) -> bool:
    if _is_system_mount(dev.get("mountpoint")):
        return True
    for child in dev.get("children") or []:
        if _has_system_mount_recursive(child):
            return True
    return False


def _is_virtual(vendor: str | None, model: str | None) -> bool:
    v = (vendor or "").strip().lower()
    m = (model or "").strip().lower()
    return "msft" in v or "virtual disk" in m


def _to_disk_info(dev: dict, dtype: str, parent: dict | None = None) -> DiskInfo:
    vendor = (dev.get("vendor") or "").strip()
    model = (dev.get("model") or "").strip()
    if parent and not vendor:
        vendor = (parent.get("vendor") or "").strip()
    if parent and not model:
        model = (parent.get("model") or "").strip()
    return DiskInfo(
        name=dev.get("name", ""),
        path="/dev/" + dev.get("name", ""),
        size=dev.get("size", ""),
        type=dtype,
        mountpoint=dev.get("mountpoint"),
        label=dev.get("label"),
        vendor=vendor,
        model=model,
    )


def _parse_lsblk(data: dict) -> list[DiskInfo]:
    result: list[DiskInfo] = []
    for dev in data.get("blockdevices") or []:
        dtype = dev.get("type", "")
        if dtype in ("loop", "rom"):
            continue
        if _is_virtual(dev.get("vendor"), dev.get("model")):
            continue
        if _has_system_mount_recursive(dev):
            continue

        children = dev.get("children") or []
        eligible_parts = [
            c for c in children
            if c.get("type") == "part" and not _has_system_mount_recursive(c)
        ]

        if eligible_parts:
            for c in eligible_parts:
                result.append(_to_disk_info(c, "part", parent=dev))
        elif dtype == "disk":
            result.append(_to_disk_info(dev, "disk"))
    return result


# ---------------------------------------------------------------------------
# mount_drive
# ---------------------------------------------------------------------------

def _get_device_at_mountpoint(mount_point: str) -> str | None:
    """Return the device currently mounted at mount_point, or None."""
    try:
        with open("/proc/mounts") as f:
            for line in f:
                parts = line.split()
                if len(parts) >= 2 and parts[1] == mount_point:
                    return parts[0]
    except Exception:
        pass
    return None


def mount_drive(params: dict) -> MountDriveResult:
    device = params.get("device")
    if not device:
        raise HandlerError("No device specified", "MISSING_PARAM")

    mount_point = params.get("mount_point") or DEFAULT_MOUNT_POINT
    if not mount_point:
        raise HandlerError("No mount_point specified and EXTERNAL_DRIVE_PATH not set", "MISSING_PARAM")

    if DEV_MOCK:
        Path(mount_point).mkdir(parents=True, exist_ok=True)
        return MountDriveResult(
            mounted=True,
            device=device,
            mount_point=mount_point,
            message=f"[DEV_MOCK] Simulated mount of {device} at {mount_point}",
        )

    # If the device is already mounted at the requested mount point, treat as success.
    if _is_mountpoint(mount_point):
        current_device = _get_device_at_mountpoint(mount_point)
        if current_device == device:
            return MountDriveResult(
                mounted=True,
                device=device,
                mount_point=mount_point,
                message=f"{device} already mounted at {mount_point}",
            )
        # Something else is mounted there — fail clearly.
        raise HandlerError(
            f"{mount_point} is already in use by {current_device}", "ALREADY_MOUNTED"
        )

    # Create mount point
    try:
        Path(mount_point).mkdir(parents=True, exist_ok=True)
    except OSError as exc:
        raise HandlerError(f"Cannot create mount point {mount_point}: {exc}", "MKDIR_FAILED") from exc

    # Detect filesystem
    try:
        fstype_result = subprocess.run(
            ["blkid", "-s", "TYPE", "-o", "value", device],
            capture_output=True, text=True, timeout=10
        )
        fstype = fstype_result.stdout.strip()
    except Exception:
        fstype = ""

    fat_like = ("vfat", "fat16", "fat32", "exfat", "ntfs", "ntfs-3g")
    unix_like = ("ext2", "ext3", "ext4", "btrfs", "xfs", "f2fs")

    if fstype in fat_like:
        opts = f"uid={CONTAINER_UID},gid={CONTAINER_GID},umask=022"
        _run_mount(device, mount_point, opts)
        return MountDriveResult(
            mounted=True,
            device=device,
            mount_point=mount_point,
            message=f"Mounted {fstype} with uid/gid options",
        )
    else:
        _run_mount(device, mount_point, None)
        try:
            os.chown(mount_point, CONTAINER_UID, CONTAINER_GID)
        except OSError as exc:
            LOG.warning("chown failed on %s: %s", mount_point, exc)
        suffix = f"Mounted {fstype} and chowned mount point" if fstype in unix_like \
            else f"Mounted (fstype={fstype or 'unknown'}), chown attempted"
        return MountDriveResult(
            mounted=True,
            device=device,
            mount_point=mount_point,
            message=suffix,
        )


def _run_mount(device: str, mount_point: str, opts: str | None) -> None:
    cmd = ["mount"]
    if opts:
        cmd += ["-o", opts]
    cmd += [device, mount_point]
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
        if r.returncode != 0:
            raise HandlerError(r.stderr.strip() or r.stdout.strip(), "MOUNT_FAILED")
    except HandlerError:
        raise
    except Exception as exc:
        raise HandlerError(str(exc), "MOUNT_FAILED") from exc


# ---------------------------------------------------------------------------
# unmount_drive
# ---------------------------------------------------------------------------

def unmount_drive(params: dict) -> UnmountDriveResult:
    mount_point = params.get("mount_point") or DEFAULT_MOUNT_POINT
    if not mount_point:
        raise HandlerError("No mount_point specified and EXTERNAL_DRIVE_PATH not set", "MISSING_PARAM")

    if DEV_MOCK:
        return UnmountDriveResult(success=True, message=f"[DEV_MOCK] Simulated unmount of {mount_point}")

    if not _is_mountpoint(mount_point):
        raise HandlerError(f"Not mounted at {mount_point}", "NOT_MOUNTED")

    try:
        r = subprocess.run(["umount", mount_point], capture_output=True, text=True, timeout=30)
        if r.returncode != 0:
            raise HandlerError(r.stderr.strip() or f"umount failed (exit {r.returncode})", "UMOUNT_FAILED")
    except HandlerError:
        raise
    except Exception as exc:
        raise HandlerError(str(exc), "UMOUNT_FAILED") from exc

    return UnmountDriveResult(success=True, message=f"Unmounted {mount_point}")


def _is_mountpoint(path: str) -> bool:
    try:
        r = subprocess.run(["mountpoint", "-q", path], timeout=5)
        return r.returncode == 0
    except Exception:
        return False


# ---------------------------------------------------------------------------
# read_device_id
# ---------------------------------------------------------------------------

def read_device_id(params: dict) -> DeviceIdResult:
    device = params.get("device")
    if not device:
        raise HandlerError("No device specified", "MISSING_PARAM")

    if DEV_MOCK:
        return DeviceIdResult(
            uuid="00000000-dev-mock-0000-000000000000",
            label="DEV-MOCK-DRIVE",
            device=device,
        )

    try:
        uuid_r = subprocess.run(
            ["blkid", "-s", "UUID", "-o", "value", device],
            capture_output=True, text=True, timeout=10
        )
        label_r = subprocess.run(
            ["blkid", "-s", "LABEL", "-o", "value", device],
            capture_output=True, text=True, timeout=10
        )
        uuid = uuid_r.stdout.strip() or None
        label = label_r.stdout.strip() or None
    except Exception as exc:
        raise HandlerError(f"blkid failed: {exc}", "BLKID_FAILED") from exc

    if not uuid:
        raise HandlerError(f"Could not read UUID for {device}", "NO_UUID")

    return DeviceIdResult(uuid=uuid, label=label, device=device)


# ---------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------

def _get_disk_usage(path: str):
    try:
        return shutil.disk_usage(path)
    except Exception:
        return None
