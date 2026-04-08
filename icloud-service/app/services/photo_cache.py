import asyncio
import logging
from typing import Optional

from app.services.icloud_session_manager import session_manager

logger = logging.getLogger(__name__)


class PhotoCache:
    """
    Per-session metadata cache for iCloud photos.

    status lifecycle: idle → fetching → ready | error
    """

    def __init__(self):
        self._cache: dict[str, dict] = {}
        self._locks: dict[str, asyncio.Lock] = {}

    def _ensure_session(self, session_id: str) -> dict:
        if session_id not in self._cache:
            self._cache[session_id] = {
                "status": "idle",
                "photos": [],
                "photo_index": {},
                "fetched": 0,
                "total": None,
            }
        return self._cache[session_id]

    def _get_lock(self, session_id: str) -> asyncio.Lock:
        if session_id not in self._locks:
            self._locks[session_id] = asyncio.Lock()
        return self._locks[session_id]

    async def start_prefetch(self, session_id: str) -> None:
        """Launch background album iteration. Safe to call multiple times — skips if already fetching."""
        state = self._ensure_session(session_id)
        if state["status"] == "fetching":
            logger.info("prefetch already running for session %s", session_id)
            return

        async with self._get_lock(session_id):
            state = self._cache[session_id]
            if state["status"] == "fetching":
                return  # double-checked after acquiring lock
            state["status"] = "fetching"
            state["fetched"] = 0
            state["total"] = None
            state["photos"] = []
            state["photo_index"] = {}
            logger.info("starting prefetch for session %s", session_id)
            try:
                await asyncio.to_thread(self._iterate_album, session_id)
                state["status"] = "ready"
                logger.info("prefetch done for session %s — %d photos", session_id, state["fetched"])
            except Exception as exc:
                state["status"] = "error"
                logger.exception("prefetch failed for session %s: %s", session_id, exc)

    def _iterate_album(self, session_id: str) -> None:
        """Sync — runs in OS thread via asyncio.to_thread. Iterates entire album, fills cache."""
        api = session_manager.get_api(session_id)
        state = self._cache[session_id]
        photos = []
        index = {}
        for photo in api.photos.all:
            d = self._photo_to_dict(photo)
            photos.append(d)
            index[photo.id] = photo  # keep actual object for download
            state["fetched"] = len(photos)
        state["photos"] = photos
        state["photo_index"] = index
        state["total"] = len(photos)

    def get_status(self, session_id: str) -> dict:
        """Return current prefetch status for a session."""
        state = self._ensure_session(session_id)
        return {
            "status": state["status"],
            "fetched": state["fetched"],
            "total": state["total"],
        }

    def get_photos(self, session_id: str) -> list[dict]:
        """Return cached photo list (empty if not yet fetched)."""
        state = self._ensure_session(session_id)
        return state["photos"]

    def get_index(self, session_id: str) -> dict:
        """Return photo_id → photo_object index (empty if not yet fetched)."""
        state = self._ensure_session(session_id)
        return state["photo_index"]

    def invalidate(self, session_id: str) -> None:
        """Reset cache for a session (e.g. after logout)."""
        self._cache.pop(session_id, None)
        self._locks.pop(session_id, None)

    @staticmethod
    def _photo_to_dict(photo) -> dict:
        original = photo.versions.get("original", {})
        return {
            "id": photo.id,
            "filename": photo.filename,
            "size": original.get("size", 0),
            "created_date": getattr(photo, "asset_date", None),
            "dimensions": {
                "width": original.get("width", 0),
                "height": original.get("height", 0),
            },
            "asset_token": photo.id,
        }


# Module-level singleton
photo_cache = PhotoCache()
