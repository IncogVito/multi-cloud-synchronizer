import asyncio

from fastapi import APIRouter, Header, Query
from fastapi.responses import StreamingResponse

from app.models.batch_delete import BatchDeleteRequest, BatchDeleteResponse, BatchDeleteResult
from app.models.photo import PhotoListResponse
from app.services.photo_cache import photo_cache
from app.services.photo_service import photo_service

router = APIRouter(prefix="/photos", tags=["photos"])


@router.post("/prefetch")
async def prefetch_photos(x_session_id: str = Header(..., alias="X-Session-ID")):
    """Trigger background album metadata fetch. Returns immediately."""
    asyncio.create_task(photo_cache.start_prefetch(x_session_id))
    return {"status": "fetching", "message": "Background fetch started"}


@router.get("/prefetch/status")
async def prefetch_status(x_session_id: str = Header(..., alias="X-Session-ID")):
    """Return current prefetch progress: {status, fetched, total}."""
    return photo_cache.get_status(x_session_id)


@router.get("", response_model=PhotoListResponse)
async def get_photos(
    limit: int = Query(100, ge=1, le=1000),
    offset: int = Query(0, ge=0),
    x_session_id: str = Header(..., alias="X-Session-ID"),
):
    """List photos — uses cache if ready, falls back to direct fetch."""
    status = photo_cache.get_status(x_session_id)
    if status["status"] == "ready":
        cached = photo_cache.get_photos(x_session_id)
        page = cached[offset: offset + limit]
        return {"photos": page}
    return await asyncio.to_thread(photo_service.get_photos, x_session_id, limit, offset)


@router.get("/thumbnail")
async def get_thumbnail(
    photo_id: str = Query(...),
    size: int = Query(256, ge=64, le=2048),
    x_session_id: str = Header(..., alias="X-Session-ID"),
):
    """Stream the thumbnail of a photo."""
    stream = photo_service.download_thumbnail_stream(x_session_id, photo_id, size)
    return StreamingResponse(stream, media_type="image/jpeg")


@router.get("/download")
async def get_photo(
    photo_id: str = Query(...),
    x_session_id: str = Header(..., alias="X-Session-ID"),
):
    """Stream the original photo binary."""
    stream = photo_service.download_photo_stream(x_session_id, photo_id)
    return StreamingResponse(stream, media_type="application/octet-stream")


@router.delete("/delete")
async def delete_photo(
    photo_id: str = Query(...),
    x_session_id: str = Header(..., alias="X-Session-ID"),
):
    """Delete a photo from iCloud."""
    await asyncio.to_thread(photo_service.delete_photo, x_session_id, photo_id)
    return {"deleted": True}


@router.post("/batch-delete", response_model=BatchDeleteResponse)
async def batch_delete_photos(
    request: BatchDeleteRequest,
    x_session_id: str = Header(..., alias="X-Session-ID"),
):
    """Delete multiple photos sequentially. iCloud does not support concurrent deletions."""
    results: list[BatchDeleteResult] = []
    for photo_id in request.photo_ids:
        for attempt in range(4):
            try:
                await asyncio.to_thread(photo_service.delete_photo, x_session_id, photo_id)
                results.append(BatchDeleteResult(photo_id=photo_id, deleted=True))
                break
            except Exception as e:
                if "TRY_AGAIN_LATER" in str(e) and attempt < 3:
                    await asyncio.sleep(2 ** attempt)
                    continue
                results.append(BatchDeleteResult(photo_id=photo_id, deleted=False, error=str(e)))
                break
    return BatchDeleteResponse(results=list(results))
