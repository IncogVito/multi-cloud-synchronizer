from typing import Optional
from pydantic import BaseModel


class PhotoInfo(BaseModel):
    id: str
    filename: str
    size: int
    created_date: Optional[int] = None  # epoch milliseconds
    dimensions: dict
    asset_token: str
    asset_record_name: Optional[str] = None


class PhotoListResponse(BaseModel):
    photos: list[PhotoInfo]
