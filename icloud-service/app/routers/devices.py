from fastapi import APIRouter
from app.models.device import DeviceInfo

router = APIRouter(prefix="/devices", tags=["devices"])


@router.get("", response_model=list[DeviceInfo])
async def get_devices():
    # TODO: Implement device listing
    return {"message": "not implemented"}
