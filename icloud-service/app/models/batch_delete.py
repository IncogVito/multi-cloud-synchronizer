from pydantic import BaseModel
from typing import Optional


class PhotoDeleteItem(BaseModel):
    photo_id: str
    asset_record_name: Optional[str] = None


class BatchDeleteRequest(BaseModel):
    photos: list[PhotoDeleteItem]


class BatchDeleteResult(BaseModel):
    photo_id: str
    deleted: bool
    error: str | None = None


class BatchDeleteResponse(BaseModel):
    results: list[BatchDeleteResult]
