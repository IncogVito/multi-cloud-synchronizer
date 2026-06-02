from unittest.mock import patch

from fastapi.testclient import TestClient

from app.exceptions import SessionNotFoundException
from app.main import app

client = TestClient(app)


def test_get_photos_returns_paginated_list():
    payload = {
        "photos": [
            {
                "id": "p1",
                "filename": "photo1.jpg",
                "size": 100,
                "created_date": "2026-03-31T00:00:00Z",
                "dimensions": {"width": 10, "height": 10},
                "asset_token": "p1",
            }
        ]
    }

    with patch("app.routers.photos.photo_service.get_photos", return_value=payload):
        response = client.get("/photos", headers={"X-Session-ID": "abc"})

    assert response.status_code == 200
    assert response.json() == payload


def test_get_photos_missing_session_header_returns_422():
    response = client.get("/photos")
    assert response.status_code == 422


def test_get_photos_inactive_session_returns_401():
    with patch("app.routers.photos.photo_service.get_photos") as mock_get_photos:
        mock_get_photos.side_effect = SessionNotFoundException("missing")

        response = client.get("/photos", headers={"X-Session-ID": "missing"})

    assert response.status_code == 401


def test_get_thumbnail_streams_thumb_version():
    def fake_stream():
        yield b"abc"

    with patch("app.routers.photos.photo_service.download_thumbnail_stream", return_value=fake_stream()):
        response = client.get("/photos/p1/thumbnail", headers={"X-Session-ID": "abc"})

    assert response.status_code == 200
    assert response.content == b"abc"
    assert "image/jpeg" in response.headers.get("content-type", "")


def test_find_photo_cache_miss_raises_not_found_no_scan():
    """Cache miss must raise PhotoNotFoundException — no fallback full scan."""
    from app.exceptions import PhotoNotFoundException
    from app.services.photo_service import PhotoService

    svc = PhotoService()

    with patch("app.services.photo_cache.photo_cache.get_index", return_value={}):
        try:
            list(svc.download_photo_stream("sess1", "nonexistent"))
            assert False, "Expected PhotoNotFoundException"
        except PhotoNotFoundException:
            pass


def test_find_photo_cache_miss_does_not_iterate_album():
    """Verify iCloud album is never iterated on cache miss."""
    from app.exceptions import PhotoNotFoundException
    from app.services.photo_service import PhotoService
    from app.services.icloud_session_manager import session_manager

    svc = PhotoService()
    mock_api = patch("app.services.icloud_session_manager.session_manager.get_api")

    with patch("app.services.photo_cache.photo_cache.get_index", return_value={}):
        with mock_api as mock_get_api:
            try:
                list(svc.download_photo_stream("sess1", "nonexistent"))
            except PhotoNotFoundException:
                pass
            mock_get_api.assert_not_called()

