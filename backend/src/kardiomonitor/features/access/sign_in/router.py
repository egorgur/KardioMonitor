"""Точка входа слайса «Вход врача»."""

from typing import Annotated

from dishka.integrations.fastapi import DishkaRoute, FromDishka
from fastapi import APIRouter, Cookie, Depends, Response

from kardiomonitor.features.access.api import (
    SESSION_COOKIE,
    CurrentClinician,
    SessionCookie,
    session_scheme,
)
from kardiomonitor.features.access.domain.authentication import (
    Authentication,
    InvalidCredentialsError,
    SignInLockedError,
)
from kardiomonitor.features.access.domain.clinician_session import ClinicianSessions, SessionToken
from kardiomonitor.platform.web.problem import ProblemError
from kardiomonitor.shared_kernel import Transaction

from .schemas import ClinicianOut, SignInIn
from .sign_in import sign_in

router = APIRouter(prefix="/api/v1/auth", tags=["Вход врача"], route_class=DishkaRoute)


@router.post("/sign-in", summary="Войти на панель")
async def sign_in_clinician(
    body: SignInIn,
    response: Response,
    authentication: FromDishka[Authentication],
    cookie: FromDishka[SessionCookie],
    transaction: FromDishka[Transaction],
) -> ClinicianOut:
    """Ставит cookie сеанса. Неверный логин и неверный пароль — один ответ; после 5 неудач
    подряд вход закрыт на 15 минут."""
    try:
        signed_in = await sign_in(
            body.login, body.password, authentication=authentication, transaction=transaction
        )
    except InvalidCredentialsError as error:
        raise ProblemError(
            status=401, code="invalid_credentials", title="Неверный логин или пароль"
        ) from error
    except SignInLockedError as error:
        raise ProblemError(
            status=429,
            code="sign_in_locked",
            title="Вход временно закрыт",
            detail="Слишком много неудачных попыток — попробуйте через 15 минут",
        ) from error
    cookie.set(response, signed_in.token)
    return ClinicianOut.from_domain(signed_in.clinician)


@router.post("/sign-out", status_code=204, summary="Выйти с панели")
async def sign_out_clinician(
    response: Response,
    sessions: FromDishka[ClinicianSessions],
    cookie: FromDishka[SessionCookie],
    kardio_session: Annotated[str | None, Cookie(alias=SESSION_COOKIE)] = None,
) -> None:
    """Завершает сеанс на сервере и снимает cookie. Без сеанса — тоже 204."""
    if kardio_session:
        await sessions.end(SessionToken(kardio_session))
    cookie.clear(response)


@router.get("/me", summary="Кто вошёл", dependencies=[Depends(session_scheme)])
async def get_signed_in_clinician(clinician: FromDishka[CurrentClinician]) -> ClinicianOut:
    """Нет сеанса — 401 `session_required`: панель показывает форму входа."""
    return ClinicianOut.from_domain(clinician)
