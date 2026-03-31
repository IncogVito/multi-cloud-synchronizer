from fastapi import APIRouter, Header, Query
from fastapi.responses import StreamingResponse

from app.models.photo import PhotoListResponse
from app.services.photo_service import photo_service

router = APIRouter(prefix="/photos", tags=["photos"])


@router.get("", response_model=PhotoListResponse)
async def get_photos(
    limit: int = Query(100, ge=1, le=1000),
    offset: int = Query(0, ge=0),
    x_session_id: str = Header(..., alias="X-Session-ID"),
):
    """List photos from the iCloud Photo Library with pagination."""
    return photo_service.get_photos(x_session_id, limit=limit, offset=offset)


@router.get("/{photo_id}/thumbnail")
async def get_thumbnail(
    photo_id: str,
    size: int = Query(256, ge=64, le=2048),
    x_session_id: str = Header(..., alias="X-Session-ID"),
):
    """Stream the thumbnail of a photo. Size is advisory (iCloud returns fixed sizes)."""
    stream = photo_service.download_thumbnail_stream(x_session_id, photo_id, size=size)
    return StreamingResponse(stream, media_type="image/jpeg")


@router.get("/{photo_id}")
async def get_photo(
    photo_id: str,
    x_session_id: str = Header(..., alias="X-Session-ID"),
):
    """Stream the original photo binary."""
    stream = photo_service.download_photo_stream(x_session_id, photo_id)
    return StreamingResponse(stream, media_type="application/octet-stream")


@router.delete("/{photo_id}")
async def delete_photo(
    photo_id: str,
    x_session_id: str = Header(..., alias="X-Session-ID"),
):
    """
    Delete a photo from iCloud.
    OPEN: icloudpy may not expose a public delete method – will raise 500 if unsupported.
    """
    photo_service.delete_photo(x_session_id, photo_id)
    return {"deleted": True}
