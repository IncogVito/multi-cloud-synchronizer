from fastapi import APIRouter, HTTPException

from app.exceptions import InvalidCredentialsException
from app.models.auth import LoginRequest, LoginResponse, SessionInfo, TwoFARequest, TwoFAResponse
from app.services.icloud_session_manager import session_manager

router = APIRouter(prefix="/auth", tags=["auth"])


@router.post("/login", response_model=LoginResponse)
async def login(request: LoginRequest):
    """Authenticate with iCloud. Returns session_id and whether 2FA is required."""
    result = session_manager.create_session(request.apple_id, request.password)
    return LoginResponse(**result)


@router.post("/2fa", response_model=TwoFAResponse)
async def two_factor_auth(request: TwoFARequest):
    """Submit 2FA / 2SA code for a pending session."""
    result = session_manager.validate_2fa(request.session_id, request.code)
    return TwoFAResponse(**result)


@router.get("/sessions", response_model=list[SessionInfo])
async def list_sessions():
    """List all known sessions (active and stale)."""
    return session_manager.list_sessions()


@router.delete("/sessions/{session_id}", status_code=204)
async def delete_session(session_id: str):
    """Remove a session. Idempotent – returns 204 even if the session did not exist."""
    session_manager.delete_session(session_id)
