from fastapi import APIRouter, Header

from app.models.device import DeviceInfo
from app.services.icloud_session_manager import session_manager

router = APIRouter(prefix="/devices", tags=["devices"])


@router.get("", response_model=list[DeviceInfo])
async def get_devices(
    x_session_id: str = Header(..., alias="X-Session-ID"),
):
    """List Apple devices associated with the iCloud account."""
    api = session_manager.get_api(x_session_id)

    result = []
    for device_id, device in api.devices.items():
        status = device.status() or {}
        location = None
        try:
            location = device.location()
        except Exception:
            pass  # location may be unavailable (Find My not enabled)

        result.append(
            DeviceInfo(
                id=device_id,
                name=status.get("name", device_id),
                model=status.get("deviceDisplayName", "Unknown"),
                battery_level=status.get("batteryLevel"),
                location=location,
            )
        )

    return result
