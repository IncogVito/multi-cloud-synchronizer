from pydantic import BaseModel


class LoginRequest(BaseModel):
    apple_id: str
    password: str


class LoginResponse(BaseModel):
    session_id: str
    requires_2fa: bool


class TwoFARequest(BaseModel):
    session_id: str
    code: str


class TwoFAResponse(BaseModel):
    session_id: str
    authenticated: bool


class SessionInfo(BaseModel):
    session_id: str
    apple_id: str
    active: bool
