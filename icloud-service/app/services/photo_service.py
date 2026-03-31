import logging
from typing import Generator

from app.exceptions import PhotoNotFoundException
from app.services.icloud_session_manager import session_manager

logger = logging.getLogger(__name__)


class PhotoService:
    """Wraps icloudpy photo operations."""

    # ------------------------------------------------------------------
    # List / metadata
    # ------------------------------------------------------------------

    def get_photos(self, session_id: str, limit: int = 100, offset: int = 0) -> dict:
        """
        Return a paginated slice of the 'All Photos' album.

        NOTE: icloudpy iterates photos lazily; to determine `total` we must
        consume the entire album.  For large libraries this can be slow.
        OPEN: Cache the photo list per session to avoid re-fetching on every call.
        """
        api = session_manager.get_api(session_id)
        album = api.photos.all

        logger.info("get_photos: fetching limit=%d offset=%d", limit, offset)

        photos_page = []
        for i, photo in enumerate(album):
            if i < offset:
                continue
            if len(photos_page) >= limit:
                break
            photos_page.append(self._photo_to_dict(photo))

        logger.info("get_photos: done – returning %d photos", len(photos_page))

        return {"photos": photos_page}

    def _photo_to_dict(self, photo) -> dict:
        original = photo.versions.get("original", {})
        # OPEN: Verify exact attribute names for dimensions on PhotoAsset.
        return {
            "id": photo.id,
            "filename": photo.filename,
            "size": original.get("size", 0),
            "created_date": getattr(photo, "asset_date", None),
            "dimensions": {
                "width": original.get("width", 0),
                "height": original.get("height", 0),
            },
            # OPEN: icloudpy exposes photo.id; a separate asset_token may differ.
            "asset_token": photo.id,
        }

    # ------------------------------------------------------------------
    # Binary download helpers
    # ------------------------------------------------------------------

    def download_photo_stream(self, session_id: str, photo_id: str) -> Generator[bytes, None, None]:
        """
        Yield raw bytes for the original photo.

        Returns a generator so FastAPI StreamingResponse can consume it lazily.
        """
        photo = self._find_photo(session_id, photo_id)
        response = photo.download()
        yield from _iter_response(response)

    def download_thumbnail_stream(
        self, session_id: str, photo_id: str, size: int = 256
    ) -> Generator[bytes, None, None]:
        """
        Yield raw bytes for the thumbnail version.

        icloudpy provides 'thumb' and 'medium'; we pick 'thumb' for small sizes,
        'medium' otherwise.
        OPEN: `size` param is advisory – iCloud returns fixed-size thumbs.
        """
        photo = self._find_photo(session_id, photo_id)
        versions = photo.versions
        version = "thumb" if size <= 256 and "thumb" in versions else "medium"
        if version not in versions:
            version = next(iter(versions), None)
        if version is None:
            raise PhotoNotFoundException(photo_id)
        response = photo.download(version)
        yield from _iter_response(response)

    # ------------------------------------------------------------------
    # Delete
    # ------------------------------------------------------------------

    def delete_photo(self, session_id: str, photo_id: str) -> bool:
        """
        Delete a photo from iCloud.

        OPEN: icloudpy's public API does not document a delete method for PhotoAsset.
              This may raise AttributeError if the method does not exist.
        """
        photo = self._find_photo(session_id, photo_id)
        if not hasattr(photo, "delete"):
            raise NotImplementedError(
                "icloudpy does not expose a public delete method for PhotoAsset"
            )
        photo.delete()
        return True

    # ------------------------------------------------------------------
    # Private helpers
    # ------------------------------------------------------------------

    def _find_photo(self, session_id: str, photo_id: str):
        """Iterate 'All Photos' to find a photo by ID. Raises PhotoNotFoundException."""
        api = session_manager.get_api(session_id)
        for photo in api.photos.all:
            if photo.id == photo_id:
                return photo
        raise PhotoNotFoundException(photo_id)


def _iter_response(response, chunk_size: int = 65536) -> Generator[bytes, None, None]:
    """Yield chunks from a streaming requests.Response object."""
    for chunk in response.iter_content(chunk_size=chunk_size):
        if chunk:
            yield chunk


# Module-level singleton.
photo_service = PhotoService()
