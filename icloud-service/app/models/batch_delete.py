from pydantic import BaseModel


class BatchDeleteRequest(BaseModel):
    photo_ids: list[str]


class BatchDeleteResult(BaseModel):
    photo_id: str
    deleted: bool
    error: str | None = None


class BatchDeleteResponse(BaseModel):
    results: list[BatchDeleteResult]
