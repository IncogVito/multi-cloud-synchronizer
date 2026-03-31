from fastapi import APIRouter
from app.models.photo import PhotoInfo, PhotoListResponse

router = APIRouter(prefix="/photos", tags=["photos"])


@router.get("", response_model=PhotoListResponse)
async def get_photos():
    # TODO: Implement photo listing
    return {"message": "not implemented"}


@router.get("/{photo_id}", response_model=PhotoInfo)
async def get_photo(photo_id: str):
    # TODO: Implement get single photo
    return {"message": "not implemented"}


@router.get("/{photo_id}/thumbnail")
async def get_thumbnail(photo_id: str):
    # TODO: Implement thumbnail retrieval
    return {"message": "not implemented"}


@router.delete("/{photo_id}")
async def delete_photo(photo_id: str):
    # TODO: Implement photo deletion
    return {"message": "not implemented"}
