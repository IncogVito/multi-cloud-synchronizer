from fastapi import APIRouter, Header

from app.services.icloud_session_manager import session_manager

router = APIRouter(tags=["health"])


@router.get("/health")
async def health():
    return {"status": "ok", "version": "1.0.0"}


@router.get("/storage")
async def storage(
    x_session_id: str = Header(..., alias="X-Session-ID"),
):
    """
    Return iCloud storage usage for the authenticated account.

    OPEN: icloudpy does not document a direct storage API.
          We attempt to read it from the account info endpoint if available.
    """
    api = session_manager.get_api(x_session_id)

    used_bytes = 0
    total_bytes = 0

    try:
        # icloudpy exposes account storage via api.account if the service is available.
        # OPEN: Verify attribute path; may vary between icloudpy versions.
        storage_info = api.account.storage
        used_bytes = storage_info.get("usageInBytes", 0)
        total_bytes = storage_info.get("totalStorageInBytes", 0)
    except Exception:
        pass  # storage info unavailable – return zeros

    return {"used_bytes": used_bytes, "total_bytes": total_bytes}
