from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse


class SessionNotFoundException(Exception):
    """Session missing or expired – maps to HTTP 401."""

    def __init__(self, session_id: str):
        self.session_id = session_id
        super().__init__(f"Session not found or expired: {session_id}")


class InvalidCredentialsException(Exception):
    """Bad Apple ID / password – maps to HTTP 401."""

    def __init__(self, message: str = "Invalid Apple ID or password"):
        super().__init__(message)


class ICloudUnavailableException(Exception):
    """iCloud API unreachable or returned an unexpected error – maps to HTTP 503."""

    def __init__(self, message: str = "iCloud service is unavailable"):
        super().__init__(message)


class PhotoNotFoundException(Exception):
    """Photo not found in the iCloud library – maps to HTTP 404."""

    def __init__(self, photo_id: str):
        self.photo_id = photo_id
        super().__init__(f"Photo not found: {photo_id}")


def register_exception_handlers(app: FastAPI) -> None:
    @app.exception_handler(SessionNotFoundException)
    async def session_not_found_handler(request: Request, exc: SessionNotFoundException):
        return JSONResponse(
            status_code=401,
            content={"detail": str(exc), "session_id": exc.session_id},
        )

    @app.exception_handler(InvalidCredentialsException)
    async def invalid_credentials_handler(request: Request, exc: InvalidCredentialsException):
        return JSONResponse(
            status_code=401,
            content={"detail": str(exc)},
        )

    @app.exception_handler(ICloudUnavailableException)
    async def icloud_unavailable_handler(request: Request, exc: ICloudUnavailableException):
        return JSONResponse(
            status_code=503,
            content={"detail": str(exc)},
        )

    @app.exception_handler(PhotoNotFoundException)
    async def photo_not_found_handler(request: Request, exc: PhotoNotFoundException):
        return JSONResponse(
            status_code=404,
            content={"detail": str(exc), "photo_id": exc.photo_id},
        )
