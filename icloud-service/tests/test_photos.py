from unittest.mock import MagicMock, patch

import pytest
from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)


def test_get_photos_returns_paginated_list():
    # TODO: Implement test – mock session + api.photos.all, GET /photos with X-Session-ID,
    # assert response has "photos" list and "total" int.
    # to be completed by secondary model
    pass


def test_get_photos_missing_session_header_returns_422():
    # TODO: Implement test – GET /photos without X-Session-ID header, assert HTTP 422.
    # to be completed by secondary model
    pass


def test_get_photos_inactive_session_returns_401():
    # TODO: Implement test – pass session_id for an inactive/stale session, assert HTTP 401.
    # to be completed by secondary model
    pass


def test_get_photos_pagination_offset_and_limit():
    # TODO: Implement test – mock 10 photos, request offset=5&limit=3, assert 3 photos returned
    # and total=10.
    # to be completed by secondary model
    pass


def test_get_photo_streams_original_binary():
    # TODO: Implement test – mock photo.download(), assert StreamingResponse with binary content.
    # to be completed by secondary model
    pass


def test_get_photo_not_found_returns_404():
    # TODO: Implement test – request non-existent photo_id, assert HTTP 404.
    # to be completed by secondary model
    pass


def test_get_thumbnail_streams_thumb_version():
    # TODO: Implement test – mock photo.versions with 'thumb', assert streaming binary response.
    # to be completed by secondary model
    pass


def test_get_thumbnail_falls_back_to_medium_when_thumb_missing():
    # TODO: Implement test – mock photo.versions without 'thumb' but with 'medium',
    # assert medium version is downloaded.
    # to be completed by secondary model
    pass


def test_delete_photo_returns_deleted_true():
    # TODO: Implement test – mock photo with delete(), assert {"deleted": true}.
    # to be completed by secondary model
    pass


def test_delete_photo_not_found_returns_404():
    # TODO: Implement test – request deletion of non-existent photo, assert HTTP 404.
    # to be completed by secondary model
    pass
