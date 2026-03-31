from typing import Optional


class PhotoService:
    """Service for interacting with iCloud Photos via icloudpy."""

    def get_photos(self, session_id: str, limit: int = 100, offset: int = 0) -> dict:
        # TODO: Implement - list photos from iCloud Photos library
        raise NotImplementedError

    def get_photo(self, session_id: str, photo_id: str) -> Optional[dict]:
        # TODO: Implement - get single photo metadata by ID
        raise NotImplementedError

    def get_thumbnail(self, session_id: str, photo_id: str) -> bytes:
        # TODO: Implement - download and return thumbnail bytes
        raise NotImplementedError

    def delete_photo(self, session_id: str, photo_id: str) -> bool:
        # TODO: Implement - delete photo from iCloud, return True if deleted
        raise NotImplementedError
