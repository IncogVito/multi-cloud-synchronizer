from fastapi import APIRouter
from app.models.auth import LoginRequest, LoginResponse, TwoFARequest, TwoFAResponse, SessionInfo

router = APIRouter(prefix="/auth", tags=["auth"])


@router.post("/login", response_model=LoginResponse)
async def login(request: LoginRequest):
    # TODO: Implement iCloud login via icloudpy
    return {"message": "not implemented"}


@router.post("/2fa", response_model=TwoFAResponse)
async def two_factor_auth(request: TwoFARequest):
    # TODO: Implement 2FA verification
    return {"message": "not implemented"}


@router.get("/sessions", response_model=list[SessionInfo])
async def list_sessions():
    # TODO: Implement session listing
    return {"message": "not implemented"}


@router.delete("/sessions/{session_id}")
async def delete_session(session_id: str):
    # TODO: Implement session deletion
    return {"message": "not implemented"}
