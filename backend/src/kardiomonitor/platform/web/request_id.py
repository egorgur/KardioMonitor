"""Идентификатор запроса: по нему ищут ошибку в журнале, а не по данным пациента."""

import re
import uuid
from collections.abc import Awaitable, Callable

from fastapi import Request, Response

REQUEST_ID_HEADER = "X-Request-ID"
_SAFE_REQUEST_ID = re.compile(r"[A-Za-z0-9._-]{1,64}")


async def assign_request_id(
    request: Request, call_next: Callable[[Request], Awaitable[Response]]
) -> Response:
    """Middleware: берёт `X-Request-ID` клиента (если он безопасный) или создаёт новый."""
    incoming = request.headers.get(REQUEST_ID_HEADER, "")
    request_id = incoming if _SAFE_REQUEST_ID.fullmatch(incoming) else uuid.uuid4().hex
    request.state.request_id = request_id
    response = await call_next(request)
    response.headers[REQUEST_ID_HEADER] = request_id
    return response


def request_id_of(request: Request) -> str | None:
    """Идентификатор текущего запроса или None, если middleware не отработал."""
    value = getattr(request.state, "request_id", None)
    return value if isinstance(value, str) else None
