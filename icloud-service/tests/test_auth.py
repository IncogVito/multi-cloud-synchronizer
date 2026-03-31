from unittest.mock import patch

from fastapi.testclient import TestClient

from app.exceptions import InvalidCredentialsException, SessionNotFoundException
from app.main import app

client = TestClient(app)


def test_login_returns_session_id_and_requires_2fa_flag():
    with patch("app.routers.auth.session_manager") as mock_manager:
        mock_manager.create_session.return_value = {
            "session_id": "abc-123",
            "requires_2fa": True,
        }

        response = client.post(
            "/auth/login",
            json={"apple_id": "user@example.com", "password": "secret"},
        )

    assert response.status_code == 200
    assert response.json() == {"session_id": "abc-123", "requires_2fa": True}
    mock_manager.create_session.assert_called_once_with("user@example.com", "secret")


def test_login_invalid_credentials_returns_401():
    with patch("app.routers.auth.session_manager") as mock_manager:
        mock_manager.create_session.side_effect = InvalidCredentialsException("Invalid login")

        response = client.post(
            "/auth/login",
            json={"apple_id": "bad@example.com", "password": "wrong"},
        )

    assert response.status_code == 401


def test_2fa_valid_code_marks_session_active():
    with patch("app.routers.auth.session_manager") as mock_manager:
        mock_manager.validate_2fa.return_value = {
            "session_id": "abc-123",
            "authenticated": True,
        }

        response = client.post(
            "/auth/2fa",
            json={"session_id": "abc-123", "code": "123456"},
        )

    assert response.status_code == 200
    assert response.json() == {"session_id": "abc-123", "authenticated": True}
    mock_manager.validate_2fa.assert_called_once_with("abc-123", "123456")


def test_2fa_unknown_session_returns_401():
    with patch("app.routers.auth.session_manager") as mock_manager:
        mock_manager.validate_2fa.side_effect = SessionNotFoundException("missing")

        response = client.post(
            "/auth/2fa",
            json={"session_id": "missing", "code": "000000"},
        )

    assert response.status_code == 401


def test_list_sessions_returns_all_sessions():
    sessions = [
        {"session_id": "A", "apple_id": "a@example.com", "active": True},
        {"session_id": "B", "apple_id": "b@example.com", "active": False},
    ]

    with patch("app.routers.auth.session_manager") as mock_manager:
        mock_manager.list_sessions.return_value = sessions

        response = client.get("/auth/sessions")

    assert response.status_code == 200
    assert response.json() == sessions


def test_delete_session_nonexistent_returns_204():
    with patch("app.routers.auth.session_manager") as mock_manager:
        mock_manager.delete_session.return_value = False

        response = client.delete("/auth/sessions/nonexistent")

    assert response.status_code == 204

