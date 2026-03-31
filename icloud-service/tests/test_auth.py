from fastapi.testclient import TestClient
from app.main import app

client = TestClient(app)


def test_login():
    # TODO: Implement test
    pass


def test_2fa():
    # TODO: Implement test
    pass


def test_list_sessions():
    # TODO: Implement test
    pass


def test_delete_session():
    # TODO: Implement test
    pass
