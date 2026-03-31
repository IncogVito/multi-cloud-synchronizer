import json
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional

from icloudpy import ICloudPyService
from icloudpy.exceptions import ICloudPyFailedLoginException

from app.exceptions import (
    ICloudUnavailableException,
    InvalidCredentialsException,
    SessionNotFoundException,
)

# File-based persistence for session metadata.
# OPEN: Consider Redis or a proper DB for production persistence.
_SESSIONS_FILE = Path("/tmp/icloud_sessions.json")

# Cookie directory base – icloudpy stores session cookies here per-session.
_COOKIE_DIR_BASE = Path("/tmp/icloud_cookies")


class ICloudSessionManager:
    """
    Manages icloudpy sessions keyed by UUID session_id.

    In-memory dict holds live ICloudPyService objects.
    Metadata (apple_id, active, created_at) is persisted to a JSON file so that
    session listings survive process restarts – however the API objects themselves
    are not restored (sessions show as inactive after restart and require re-login).
    """

    def __init__(self):
        self._sessions: dict[str, dict] = {}  # session_id -> {apple_id, api, active, created_at}
        self._load_metadata()

    # ------------------------------------------------------------------
    # Persistence helpers
    # ------------------------------------------------------------------

    def _load_metadata(self) -> None:
        """Load persisted session metadata. API objects cannot be restored."""
        if not _SESSIONS_FILE.exists():
            return
        try:
            data = json.loads(_SESSIONS_FILE.read_text())
            for session_id, meta in data.items():
                self._sessions[session_id] = {
                    "apple_id": meta["apple_id"],
                    "api": None,  # not restorable after restart
                    "active": False,  # stale; user must re-login
                    "created_at": meta.get("created_at", ""),
                }
        except Exception:
            pass  # corrupt file – start fresh

    def _save_metadata(self) -> None:
        """Persist session metadata to file."""
        _SESSIONS_FILE.parent.mkdir(parents=True, exist_ok=True)
        data = {
            sid: {
                "apple_id": s["apple_id"],
                "active": s["active"],
                "created_at": s.get("created_at", ""),
            }
            for sid, s in self._sessions.items()
        }
        _SESSIONS_FILE.write_text(json.dumps(data, indent=2))

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def create_session(self, apple_id: str, password: str) -> dict:
        """
        Authenticate with iCloud and create a new session.

        Returns:
            {"session_id": str, "requires_2fa": bool}
        Raises:
            InvalidCredentialsException – bad apple_id / password.
            ICloudUnavailableException  – network or iCloud error.
        """
        cookie_dir = _COOKIE_DIR_BASE / apple_id.replace("@", "_").replace(".", "_")
        cookie_dir.mkdir(parents=True, exist_ok=True)

        try:
            api = ICloudPyService(apple_id, password, cookie_directory=str(cookie_dir))
        except ICloudPyFailedLoginException as exc:
            raise InvalidCredentialsException(str(exc)) from exc
        except Exception as exc:
            raise ICloudUnavailableException(str(exc)) from exc

        session_id = str(uuid.uuid4())
        requires_2fa = bool(api.requires_2fa or api.requires_2sa)

        self._sessions[session_id] = {
            "apple_id": apple_id,
            "api": api,
            "active": not requires_2fa,
            "created_at": datetime.now(timezone.utc).isoformat(),
        }
        self._save_metadata()

        return {"session_id": session_id, "requires_2fa": requires_2fa}

    def validate_2fa(self, session_id: str, code: str) -> dict:
        """
        Validate a 2FA / 2SA code for an existing session.

        Returns:
            {"session_id": str, "authenticated": bool}
        """
        session = self._require_session(session_id)
        api: Optional[ICloudPyService] = session["api"]

        if api is None:
            # Session was loaded from file after restart – API object unavailable.
            raise SessionNotFoundException(session_id)

        try:
            if api.requires_2fa:
                result = bool(api.validate_2fa_code(code))
                if result and not api.is_trusted_session:
                    api.trust_session()
            elif api.requires_2sa:
                # OPEN: 2SA requires device selection; we auto-pick the first device.
                devices = api.trusted_devices
                if not devices:
                    raise ICloudUnavailableException("No trusted devices available for 2SA")
                device = devices[0]
                api.send_verification_code(device)
                result = bool(api.validate_verification_code(device, code))
            else:
                result = True  # 2FA not required for this session
        except (InvalidCredentialsException, ICloudUnavailableException, SessionNotFoundException):
            raise
        except Exception as exc:
            raise ICloudUnavailableException(str(exc)) from exc

        session["active"] = result
        self._save_metadata()

        return {"session_id": session_id, "authenticated": result}

    def get_api(self, session_id: str) -> ICloudPyService:
        """
        Return the live ICloudPyService for an active session.

        Raises SessionNotFoundException (→ 401) if session is missing or inactive.
        """
        session = self._sessions.get(session_id)
        if not session or session["api"] is None or not session["active"]:
            raise SessionNotFoundException(session_id)
        return session["api"]

    def get_session(self, session_id: str) -> Optional[dict]:
        return self._sessions.get(session_id)

    def delete_session(self, session_id: str) -> bool:
        if session_id not in self._sessions:
            return False
        del self._sessions[session_id]
        self._save_metadata()
        return True

    def list_sessions(self) -> list[dict]:
        return [
            {
                "session_id": sid,
                "apple_id": s["apple_id"],
                "active": s["active"],
            }
            for sid, s in self._sessions.items()
        ]

    # ------------------------------------------------------------------
    # Private helpers
    # ------------------------------------------------------------------

    def _require_session(self, session_id: str) -> dict:
        session = self._sessions.get(session_id)
        if not session:
            raise SessionNotFoundException(session_id)
        return session


# Module-level singleton – used by routers via import.
session_manager = ICloudSessionManager()
