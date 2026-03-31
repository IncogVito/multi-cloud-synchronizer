from unittest.mock import MagicMock, patch

from fastapi.testclient import TestClient

from app.exceptions import SessionNotFoundException
from app.main import app

client = TestClient(app)


def test_health_endpoint_returns_ok():
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok", "version": "1.0.0"}


def test_storage_requires_session_header():
    response = client.get("/storage")
    assert response.status_code == 422


def test_storage_invalid_session_returns_401():
    with patch("app.routers.health.session_manager.get_api") as mock_get_api:
        mock_get_api.side_effect = SessionNotFoundException("missing")

        response = client.get("/storage", headers={"X-Session-ID": "missing"})

    assert response.status_code == 401


def test_storage_returns_used_and_total_bytes():
    mock_account = MagicMock(storage={"usageInBytes": 12345, "totalStorageInBytes": 98765})
    mock_api = MagicMock(account=mock_account)

    with patch("app.routers.health.session_manager.get_api", return_value=mock_api):
        response = client.get("/storage", headers={"X-Session-ID": "active"})

    assert response.status_code == 200
    assert response.json() == {"used_bytes": 12345, "total_bytes": 98765}

