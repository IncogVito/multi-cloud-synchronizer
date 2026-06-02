import json
import logging
from typing import Generator
from urllib.parse import urlencode

from icloudpy.services.photos import PhotoAsset as ICloudPhotoAsset

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
        dt = getattr(photo, "asset_date", None)
        ts = dt.timestamp() if dt else 0
        created_ms = int(ts * 1000) if ts > 0 else None
        return {
            "id": photo.id,
            "filename": photo.filename,
            "size": original.get("size", 0),
            "created_date": created_ms,
            "dimensions": {
                "width": original.get("width", 0),
                "height": original.get("height", 0),
            },
            "asset_token": photo.id,
            "asset_record_name": photo._asset_record.get("recordName"),
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

    def delete_photo(self, session_id: str, photo_id: str, asset_record_name: str | None = None) -> bool:
        logger.info("delete_photo: photo_id=%s asset_record_name=%s", photo_id, asset_record_name)
        if not asset_record_name:
            raise ValueError(f"asset_record_name required for delete, photo_id={photo_id}")
        return self._delete_photo_direct(session_id, photo_id, asset_record_name)

    def _delete_photo_direct(self, session_id: str, master_record_name: str, asset_record_name: str) -> bool:
        """O(1) delete via direct CloudKit records/lookup — no cache or full scan needed."""
        api = session_manager.get_api(session_id)
        album = api.photos.all
        url = (
            f"{album.service._service_endpoint}/records/lookup?"
            + urlencode(album.service.params)
        )
        data = json.dumps({
            "records": [
                {"recordName": master_record_name},
                {"recordName": asset_record_name},
            ],
            "zoneID": album.service.zone_id,
        })
        resp = album.service.session.post(url, data=data, headers={"Content-type": "text/plain"})
        resp.raise_for_status()
        records = resp.json().get("records", [])
        master = next((r for r in records if r.get("recordType") == "CPLMaster"), None)
        asset = next((r for r in records if r.get("recordType") == "CPLAsset"), None)
        if not master or not asset:
            raise PhotoNotFoundException(master_record_name)
        photo = ICloudPhotoAsset(album.service, master, asset)
        photo.delete()
        logger.info("delete_photo_direct: success photo_id=%s", master_record_name)
        return True

    # ------------------------------------------------------------------
    # Private helpers
    # ------------------------------------------------------------------

    def _find_photo(self, session_id: str, photo_id: str):
        """O(1) lookup from cache only. No fallback scan — if not in cache, photo is not found."""
        from app.services.photo_cache import photo_cache  # local import to avoid circular
        index = photo_cache.get_index(session_id)
        if photo_id in index:
            return index[photo_id]
        raise PhotoNotFoundException(photo_id)


def _iter_response(response, chunk_size: int = 65536) -> Generator[bytes, None, None]:
    """Yield chunks from a streaming requests.Response object."""
    for chunk in response.iter_content(chunk_size=chunk_size):
        if chunk:
            yield chunk


# Module-level singleton.
photo_service = PhotoService()
