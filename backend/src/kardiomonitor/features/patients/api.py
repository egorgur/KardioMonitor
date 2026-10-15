"""Опубликованный API контекста «Пациенты» для других контекстов и слайсов.

`CurrentPatient` — пациент, которому принадлежит токен запроса. Его отдаёт dishka
(`PatientsProvider`); без верного токена — `NotAuthenticatedError` → 401.
"""

from typing import NewType
from uuid import UUID

from fastapi.security import HTTPBearer

from kardiomonitor.features.patients.domain.patient import Patient, PatientId
from kardiomonitor.features.patients.domain.patient_registry import PatientRegistry
from kardiomonitor.platform.web.problem import ProblemError

CurrentPatient = NewType("CurrentPatient", Patient)

bearer_scheme = HTTPBearer(
    auto_error=False,
    description="Токен загрузки, выданный при регистрации телефона",
)
"""Только для OpenAPI (кнопка Authorize в /docs); проверку делает `PatientsProvider`."""


class NotAuthenticatedError(Exception):
    """Нет токена загрузки, или сервер его не знает."""


async def require_patient(registry: PatientRegistry, patient_id: UUID) -> Patient:
    """Пациент по идентификатору из пути запроса; нет такого — 404."""
    patient = await registry.by_id(PatientId(patient_id))
    if patient is None:
        raise ProblemError(status=404, code="patient_not_found", title="Пациент не найден")
    return patient
