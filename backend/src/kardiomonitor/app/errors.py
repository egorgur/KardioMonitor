"""Исключения → ответ `application/problem+json` (api-contract.md, «Ошибки»)."""

import logging
from collections.abc import Callable

from fastapi import FastAPI, Request, Response, status
from fastapi.exceptions import RequestValidationError
from starlette.exceptions import HTTPException

from kardiomonitor.features.access.api import NotSignedInError
from kardiomonitor.features.patients.api import NotAuthenticatedError
from kardiomonitor.platform.web.problem import (
    ProblemDetail,
    ProblemError,
    problem_response,
    problem_type,
)
from kardiomonitor.platform.web.request_id import request_id_of
from kardiomonitor.shared_kernel import DomainRuleViolation

logger = logging.getLogger(__name__)


def install_error_handlers(app: FastAPI) -> None:
    """Каждая ошибка API — ProblemDetail с `code` и `request_id`."""
    _register(app, DomainRuleViolation, _domain_rule_violation)
    _register(app, ProblemError, _problem_error)
    _register(
        app, NotAuthenticatedError, _not_authenticated, headers={"WWW-Authenticate": "Bearer"}
    )
    _register(app, NotSignedInError, _not_signed_in)
    _register(app, RequestValidationError, _invalid_request)
    _register(app, HTTPException, _http_error)
    _register(app, Exception, _internal_error)


def _register[E: Exception](
    app: FastAPI,
    kind: type[E],
    render: Callable[[Request, E], ProblemDetail],
    headers: dict[str, str] | None = None,
) -> None:
    async def handle(request: Request, error: Exception) -> Response:
        if not isinstance(error, kind):  # Starlette передаёт исключение зарегистрированного типа
            raise TypeError(f"обработчик {kind.__name__} получил {type(error).__name__}")
        return problem_response(render(request, error), headers)

    app.add_exception_handler(kind, handle)


def _problem(
    request: Request, *, status_code: int, code: str, title: str, detail: str | None = None
) -> ProblemDetail:
    return ProblemDetail(
        type=problem_type(code),
        title=title,
        status=status_code,
        detail=detail,
        code=code,
        request_id=request_id_of(request),
    )


def _domain_rule_violation(request: Request, error: DomainRuleViolation) -> ProblemDetail:
    return _problem(
        request,
        status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
        code=error.code,
        title="Нарушено правило данных",
        detail=str(error),
    )


def _problem_error(request: Request, error: ProblemError) -> ProblemDetail:
    return _problem(
        request, status_code=error.status, code=error.code, title=error.title, detail=error.detail
    )


def _not_authenticated(request: Request, _error: NotAuthenticatedError) -> ProblemDetail:
    return _problem(
        request,
        status_code=status.HTTP_401_UNAUTHORIZED,
        code="unauthorized",
        title="Телефон не зарегистрирован",
        detail="Нет токена загрузки или сервер его не знает — зарегистрируйтесь заново",
    )


def _not_signed_in(request: Request, _error: NotSignedInError) -> ProblemDetail:
    return _problem(
        request,
        status_code=status.HTTP_401_UNAUTHORIZED,
        code="session_required",
        title="Войдите на панель",
        detail="Нет сеанса врача, или он истёк",
    )


def _invalid_request(request: Request, error: RequestValidationError) -> ProblemDetail:
    problem = _problem(
        request,
        status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
        code="invalid_request",
        title="Неверный запрос",
    )
    errors = [
        {"loc": list(item["loc"]), "msg": item["msg"], "type": item["type"]}
        for item in error.errors()
    ]
    return problem.model_copy(update={"errors": errors})


def _http_error(request: Request, error: HTTPException) -> ProblemDetail:
    code = "not_found" if error.status_code == status.HTTP_404_NOT_FOUND else "http_error"
    return _problem(request, status_code=error.status_code, code=code, title=str(error.detail))


def _internal_error(request: Request, error: Exception) -> ProblemDetail:
    logger.error("необработанная ошибка, request_id=%s", request_id_of(request), exc_info=error)
    return _problem(
        request,
        status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
        code="internal_error",
        title="Ошибка сервера",
    )
