"""Точка входа слайса «Регистрация»."""

from dishka.integrations.fastapi import DishkaRoute, FromDishka
from fastapi import APIRouter, Depends

from kardiomonitor.features.patients.api import CurrentPatient, bearer_scheme
from kardiomonitor.features.patients.domain.patient_registry import PatientRegistry
from kardiomonitor.shared_kernel import Clock, Transaction

from .register_patient import register_patient
from .schemas import PatientProfileOut, RegistrationIn, RegistrationOut

router = APIRouter(prefix="/api/v1", tags=["Пациенты"], route_class=DishkaRoute)


@router.post("/register", status_code=201, summary="Зарегистрировать пациента и его телефон")
async def register(
    body: RegistrationIn,
    registry: FromDishka[PatientRegistry],
    clock: FromDishka[Clock],
    transaction: FromDishka[Transaction],
) -> RegistrationOut:
    """Создаёт пациента и выдаёт токен загрузки. Токен в ответе — единственный раз."""
    registration = await register_patient(
        body.name, body.time_zone, registry=registry, clock=clock, transaction=transaction
    )
    return RegistrationOut.from_domain(registration)


@router.get(
    "/me",
    summary="Кому принадлежит токен",
    dependencies=[Depends(bearer_scheme)],
)
async def get_profile(patient: FromDishka[CurrentPatient]) -> PatientProfileOut:
    """Проверка токена приложением: 200 — токен действителен, 401 — нет."""
    return PatientProfileOut.from_domain(patient)
