from unittest.mock import AsyncMock, patch

import pytest
from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)

HEADERS = {"X-Session-ID": "test-session"}


def test_batch_delete_all_succeed():
    with patch("app.routers.photos.photo_service.delete_photo") as mock_del:
        mock_del.return_value = None
        resp = client.post(
            "/photos/batch-delete",
            json={"photo_ids": ["p1", "p2"]},
            headers=HEADERS,
        )

    assert resp.status_code == 200
    results = resp.json()["results"]
    assert len(results) == 2
    assert all(r["deleted"] for r in results)
    assert all(r["error"] is None for r in results)


def test_batch_delete_partial_failure():
    def fail_on_p2(session_id, photo_id):
        if photo_id == "p2":
            raise RuntimeError("iCloud error")

    with patch("app.routers.photos.photo_service.delete_photo", side_effect=fail_on_p2):
        resp = client.post(
            "/photos/batch-delete",
            json={"photo_ids": ["p1", "p2"]},
            headers=HEADERS,
        )

    assert resp.status_code == 200
    results = {r["photo_id"]: r for r in resp.json()["results"]}
    assert results["p1"]["deleted"] is True
    assert results["p2"]["deleted"] is False
    assert "iCloud error" in results["p2"]["error"]


def test_batch_delete_empty_list():
    resp = client.post("/photos/batch-delete", json={"photo_ids": []}, headers=HEADERS)
    assert resp.status_code == 200
    assert resp.json()["results"] == []


def test_batch_delete_missing_session_header_returns_422():
    resp = client.post("/photos/batch-delete", json={"photo_ids": ["p1"]})
    assert resp.status_code == 422


def test_batch_delete_all_fail():
    with patch("app.routers.photos.photo_service.delete_photo", side_effect=Exception("network error")):
        resp = client.post(
            "/photos/batch-delete",
            json={"photo_ids": ["p1", "p2", "p3"]},
            headers=HEADERS,
        )

    assert resp.status_code == 200
    results = resp.json()["results"]
    assert all(not r["deleted"] for r in results)
    assert all(r["error"] is not None for r in results)
