from __future__ import annotations
from dataclasses import dataclass


@dataclass
class DriveStatus:
    available: bool
    path: str | None
    free_bytes: int | None
    total_bytes: int | None = None


@dataclass
class DiskInfo:
    name: str
    path: str
    size: str
    type: str
    mountpoint: str | None
    label: str | None
    vendor: str
    model: str


@dataclass
class MountDriveResult:
    mounted: bool
    device: str
    mount_point: str
    message: str


@dataclass
class UnmountDriveResult:
    success: bool
    message: str


@dataclass
class DeviceIdResult:
    uuid: str | None
    label: str | None
    device: str


@dataclass
class IPhoneDetectResult:
    connected: bool
    device_name: str | None
    udid: str | None


@dataclass
class IPhoneListDevicesResult:
    devices: list[str]


@dataclass
class IPhoneTrustResult:
    trusted: bool
    udid: str | None
    message: str


@dataclass
class IPhoneInfoResult:
    udid: str
    device_name: str
    product_type: str
    ios_version: str
    serial_number: str
    total_capacity_bytes: int | None
    free_bytes: int | None
    battery_percent: int | None


@dataclass
class IPhoneMountResult:
    mounted: bool
    mount_path: str | None
    udid: str | None
    error: str | None


@dataclass
class IPhoneUnmountResult:
    unmounted: bool
    error: str | None
