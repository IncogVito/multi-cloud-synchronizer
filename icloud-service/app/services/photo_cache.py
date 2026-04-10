import asyncio
import json
import logging
import threading
import time
import concurrent.futures
from urllib.parse import urlencode

from icloudpy.services.photos import PhotoAsset

from app.services.icloud_session_manager import session_manager

logger = logging.getLogger(__name__)

# icloudpy fetches page_size * 2 records per request (CPLAsset + CPLMaster pairs).
# page_size=100 (icloudpy default) → resultsLimit=200 → 100 photos per HTTP request.
# Do NOT increase page_size: iCloud silently caps resultsLimit at 200, so a larger
# page_size still returns 100 photos but our offset would jump by 200, skipping half.
_PAGE_SIZE = 100
# Initial parallel HTTP connections to iCloud. Reduced automatically on 429.
_MAX_WORKERS = 5
_MAX_RETRIES = 4
# Seconds to wait after 429 when iCloud doesn't send a Retry-After header.
_RETRY_AFTER_DEFAULT = 10.0


class _ConcurrencyLimiter:
    """
    Semaphore-based concurrency limiter that supports permanent reduction.

    throttle() atomically decrements the limit by 1 (floor: 1) and permanently
    consumes one slot so the pool never grows back.  The lock guarantees only
    one thread can reduce at a time; the acquire() is intentionally outside
    the lock to avoid deadlock.
    """

    def __init__(self, n: int) -> None:
        self._sem = threading.Semaphore(n)
        self._lock = threading.Lock()
        self._current = n

    @property
    def current(self) -> int:
        return self._current

    def acquire(self) -> None:
        self._sem.acquire()

    def release(self) -> None:
        self._sem.release()

    def throttle(self) -> int:
        """
        Reduce limit by 1 and return the new value.
        Idempotent at 1 — will not reduce below a single worker.
        """
        with self._lock:
            if self._current <= 1:
                return 1
            self._current -= 1
            # Visible to other threads immediately; acquire happens outside lock.
        self._sem.acquire()  # permanently consumes one slot — never released
        return self._current


class PhotoCache:
    """
    Per-session metadata cache for iCloud photos.

    status lifecycle: idle → fetching → ready | error

    Prefetch strategy: get total count first, then fetch all pages in parallel
    using ThreadPoolExecutor.  iCloud's CloudKit API is offset-based (startRank)
    so pages are independent — no cursor dependency between them.

    Rate-limiting: on HTTP 429 the worker logs the event, reduces the shared
    _ConcurrencyLimiter by 1, sleeps Retry-After seconds, then retries.
    This converges naturally toward a concurrency level that iCloud tolerates.
    """

    def __init__(self) -> None:
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
            state.update({"status": "fetching", "fetched": 0, "total": None, "photos": [], "photo_index": {}})
            logger.info("starting prefetch for session %s", session_id)
            try:
                await asyncio.to_thread(self._iterate_album, session_id)
                state["status"] = "ready"
                logger.info("prefetch done for session %s — %d photos", session_id, state["fetched"])
            except Exception as exc:
                state["status"] = "error"
                logger.exception("prefetch failed for session %s: %s", session_id, exc)

    def _fetch_page(
        self, album, offset: int, limiter: _ConcurrencyLimiter
    ) -> list[PhotoAsset]:
        """
        Fetch one page from iCloud starting at `offset`.

        Designed for parallel calls from multiple threads:
        - acquires the shared limiter around each HTTP request
        - on 429: releases limiter, throttles the pool, sleeps, retries
        - raises on non-recoverable errors or exhausted retries
        """
        url = (
            f"{album.service._service_endpoint}/records/query?"
            + urlencode(album.service.params)
        )
        query_data = json.dumps(
            album._list_query_gen(
                offset, album.list_type, album.direction, album.query_filter
            )
        )

        resp = None
        for attempt in range(1, _MAX_RETRIES + 1):
            limiter.acquire()
            try:
                resp = album.service.session.post(
                    url,
                    data=query_data,
                    headers={"Content-type": "text/plain"},
                )
            finally:
                limiter.release()

            if resp.status_code != 429:
                break

            # --- 429 handling ---
            retry_after = float(resp.headers.get("Retry-After", _RETRY_AFTER_DEFAULT))
            new_limit = limiter.throttle()
            logger.warning(
                "iCloud 429 at offset=%d (attempt %d/%d) — "
                "reducing concurrency to %d worker(s), retrying in %.1fs",
                offset, attempt, _MAX_RETRIES, new_limit, retry_after,
            )
            if attempt < _MAX_RETRIES:
                time.sleep(retry_after)
            else:
                resp.raise_for_status()  # exhausted retries → propagate as error

        resp.raise_for_status()  # raise on any other 4xx / 5xx
        response = resp.json()

        asset_records: dict[str, dict] = {}
        master_records: list[dict] = []
        for rec in response["records"]:
            if rec["recordType"] == "CPLAsset":
                master_id = rec["fields"]["masterRef"]["value"]["recordName"]
                asset_records[master_id] = rec
            elif rec["recordType"] == "CPLMaster":
                master_records.append(rec)

        return [
            PhotoAsset(album.service, mr, asset_records[mr["recordName"]])
            for mr in master_records
            if mr["recordName"] in asset_records
        ]

    def _iterate_album(self, session_id: str) -> None:
        """Sync — runs in OS thread via asyncio.to_thread. Fetches all pages in parallel."""
        api = session_manager.get_api(session_id)
        state = self._cache[session_id]
        album = api.photos.all
        # Do not override album.page_size — keep the default (100) so that
        # resultsLimit=200 and the API returns exactly 100 photos per page.
        # Our parallel offsets (range(0, total, _PAGE_SIZE)) must match that count.

        # One HTTP call to get the authoritative count before spawning workers.
        total = len(album)
        state["total"] = total
        offsets = list(range(0, total, _PAGE_SIZE))
        logger.info(
            "session %s: iCloud reports %d photos — queuing %d pages "
            "(page_size=%d, initial workers=%d)",
            session_id, total, len(offsets), _PAGE_SIZE, _MAX_WORKERS,
        )

        if total == 0:
            return

        page_results: dict[int, list[PhotoAsset]] = {}
        limiter = _ConcurrencyLimiter(_MAX_WORKERS)

        with concurrent.futures.ThreadPoolExecutor(max_workers=_MAX_WORKERS) as pool:
            futures = {
                pool.submit(self._fetch_page, album, off, limiter): off
                for off in offsets
            }
            total_pages = len(futures)
            for future in concurrent.futures.as_completed(futures):
                offset = futures[future]
                page = future.result()  # propagates exceptions → sets state to error
                page_results[offset] = page
                state["fetched"] = sum(len(v) for v in page_results.values())
                pages_done = len(page_results)
                logger.info(
                    "session %s: page offset=%-6d → %3d photos  |  "
                    "progress %d/%d pages, %d/%d photos fetched  |  workers=%d",
                    session_id, offset, len(page),
                    pages_done, total_pages,
                    state["fetched"], total,
                    limiter.current,
                )

        # Reassemble in original sort order.
        photos: list[dict] = []
        index: dict[str, PhotoAsset] = {}
        for off in sorted(page_results):
            for photo in page_results[off]:
                photos.append(self._photo_to_dict(photo))
                index[photo.id] = photo

        actual = len(photos)
        if actual != total:
            logger.warning(
                "session %s: iCloud reported %d photos but received %d — "
                "using actual count (library may have changed during fetch)",
                session_id, total, actual,
            )
        state["photos"] = photos
        state["photo_index"] = index
        state["fetched"] = actual
        state["total"] = actual  # reconcile with actual delivery count

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
    def _photo_to_dict(photo: PhotoAsset) -> dict:
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
