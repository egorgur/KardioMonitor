"""Опубликованный API контекста «Доступ» для других контекстов и слайсов.

`CurrentClinician` — врач, вошедший на панель; его отдаёт dishka (`AccessProvider`) по cookie
сеанса. Нет сеанса или он истёк — `NotSignedInError` → 401 `session_required`.
"""

from dataclasses import dataclass
from typing import NewType

from fastapi import Response
from fastapi.security import APIKeyCookie

from kardiomonitor.features.access.domain.clinician import Clinician
from kardiomonitor.features.access.domain.clinician_session import (
    SESSION_IDLE_TIMEOUT,
    SessionToken,
)

CurrentClinician = NewType("CurrentClinician", Clinician)

SESSION_COOKIE = "kardio_session"
SESSION_COOKIE_PATH = "/api"

session_scheme = APIKeyCookie(
    name=SESSION_COOKIE,
    auto_error=False,
    description="Сеанс врача: cookie ставит POST /api/v1/auth/sign-in",
)
"""Только для OpenAPI; проверку делает `AccessProvider`."""


class NotSignedInError(Exception):
    """Нет сеанса врача, или он истёк, или завершён."""


@dataclass(frozen=True, slots=True)
class SessionCookie:
    """Как ставить и снимать cookie сеанса (ADR-0011). `is_secure` — только HTTPS; выключается
    настройкой `KARDIO_SECURE_COOKIES=false` для разработки без HTTPS не на localhost."""

    is_secure: bool

    def set(self, response: Response, token: SessionToken) -> None:
        response.set_cookie(
            SESSION_COOKIE,
            token.value,
            max_age=int(SESSION_IDLE_TIMEOUT.total_seconds()),
            path=SESSION_COOKIE_PATH,
            secure=self.is_secure,
            httponly=True,
            samesite="strict",
        )

    def clear(self, response: Response) -> None:
        response.delete_cookie(
            SESSION_COOKIE,
            path=SESSION_COOKIE_PATH,
            secure=self.is_secure,
            httponly=True,
            samesite="strict",
        )
