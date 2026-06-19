"""Tests for stale-mount detection in check_drive and remount recovery.

Regression: after a USB drive is yanked and reinserted, the old mount goes
stale. `mountpoint -q` still returns 0 and `shutil.disk_usage` (statvfs) still
returns cached superblock numbers, so check_drive used to report
available=True even though every real read fails with EIO. The fix performs an
actual directory read to surface the I/O error.
"""
import errno
import os

import handlers.drive as drive


class _FakeUsage:
    free = 142208008192
    total = 500088438784


def test_check_drive_reports_unavailable_on_stale_mount(monkeypatch):
    path = "/mnt/external-drive"

    # Looks like a healthy, still-listed mountpoint to the cached APIs...
    monkeypatch.setattr(drive.Path, "is_dir", lambda self: True)
    monkeypatch.setattr(drive, "_is_mountpoint", lambda p: True)
    monkeypatch.setattr(drive, "_get_disk_usage", lambda p: _FakeUsage())

    # ...but the real readdir syscall fails with EIO (stale mount).
    def boom(_p):
        raise OSError(errno.EIO, "Input/output error")

    monkeypatch.setattr(drive, "_probe_io", boom)

    status = drive.check_drive({"mount_path": path})

    assert status.available is False
    assert status.free_bytes is None


def test_check_drive_reports_available_on_healthy_mount(monkeypatch):
    path = "/mnt/external-drive"
    monkeypatch.setattr(drive.Path, "is_dir", lambda self: True)
    monkeypatch.setattr(drive, "_is_mountpoint", lambda p: True)
    monkeypatch.setattr(drive, "_get_disk_usage", lambda p: _FakeUsage())
    monkeypatch.setattr(drive, "_probe_io", lambda p: None)  # readdir succeeds

    status = drive.check_drive({"mount_path": path})

    assert status.available is True
    assert status.free_bytes == _FakeUsage.free


def test_probe_io_raises_on_stale_dir(monkeypatch):
    """_probe_io must actually trigger a readdir, not just stat the path."""

    class _StaleScandir:
        def __enter__(self):
            return self

        def __exit__(self, *a):
            return False

        def __iter__(self):
            return self

        def __next__(self):
            raise OSError(errno.ESTALE, "Stale file handle")

    monkeypatch.setattr(os, "scandir", lambda p: _StaleScandir())

    try:
        drive._probe_io("/mnt/external-drive")
        assert False, "expected OSError from stale readdir"
    except OSError as exc:
        assert exc.errno == errno.ESTALE


def test_remount_drive_resolves_renamed_device_by_uuid(monkeypatch):
    """After re-plug, sdb1 -> sdc1: remount must follow the UUID, force-unmount
    the stale target, and mount the freshly-resolved node."""
    calls = {}

    monkeypatch.setattr(drive, "_is_mountpoint", lambda p: True)
    monkeypatch.setattr(drive, "_force_unmount", lambda p: calls.setdefault("unmounted", p))
    monkeypatch.setattr(drive, "_resolve_device_by_uuid", lambda u: "/dev/sdc1")

    def fake_mount(params):
        calls["mounted_device"] = params["device"]
        from models import MountDriveResult
        return MountDriveResult(mounted=True, device=params["device"],
                                mount_point=params["mount_point"], message="ok")

    monkeypatch.setattr(drive, "mount_drive", fake_mount)

    result = drive.remount_drive({
        "mount_point": "/mnt/external-drive",
        "uuid": "1234-ABCD",
        "device": "/dev/sdb1",  # stale, must be ignored in favour of UUID
    })

    assert calls["unmounted"] == "/mnt/external-drive"
    assert calls["mounted_device"] == "/dev/sdc1"
    assert result.mounted is True
    assert result.device == "/dev/sdc1"
