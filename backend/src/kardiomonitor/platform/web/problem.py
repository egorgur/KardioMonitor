"""Ошибки API в формате RFC 9457 (`application/problem+json`), см. api-contract.md."""

from typing import Any

from fastapi.responses import JSONResponse
from pydantic import BaseModel

PROBLEM_MEDIA_TYPE = "application/problem+json"


class ProblemDetail(BaseModel):
    """Тело ответа об ошибке. Клиент решает, что делать, по `code`, а не по тексту."""

    type: str
    title: str
    status: int
    detail: str | None = None
    code: str
    request_id: str | None = None
    errors: list[dict[str, Any]] | None = None


class ProblemError(Exception):
    """Ожидаемый исход, который эндпоинт отдаёт клиенту как ошибку: 404, 409 и т. п."""

    def __init__(self, *, status: int, code: str, title: str, detail: str | None = None) -> None:
        super().__init__(title)
        self.status = status
        self.code = code
        self.title = title
        self.detail = detail


def problem_response(problem: ProblemDetail, headers: dict[str, str] | None = None) -> JSONResponse:
    """Ответ с телом ProblemDetail и правильным media type."""
    return JSONResponse(
        problem.model_dump(exclude_none=True),
        status_code=problem.status,
        media_type=PROBLEM_MEDIA_TYPE,
        headers=headers,
    )


def problem_type(code: str) -> str:
    """Относительный URI типа ошибки (RFC 9457 допускает относительные)."""
    return "/problems/" + code.replace("_", "-")
