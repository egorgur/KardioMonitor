"""Опубликованный API контекста «Пациенты» для других контекстов и слайсов.

- `CurrentPatient` — пациент, которому принадлежит токен запроса (телефон); без верного токена —
  `NotAuthenticatedError` → 401.
- `AttendedPatients` — пациенты врача, вошедшего на панель (ТР-11). **Единственный** путь к
  пациенту для эндпоинтов панели: чужой пациент отвечает 404, как несуществующий.
"""

from typing import NewType
from uuid import UUID

from fastapi.security import HTTPBearer

from kardiomonitor.features.access.api import CurrentClinician
from kardiomonitor.features.access.domain.access_log import AccessAction, AccessLog
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


class AttendedPatients:
    """Пациенты вошедшего врача. Отдаёт dishka (`PatientsProvider`) — только при сеансе врача."""

    def __init__(
        self, registry: PatientRegistry, clinician: CurrentClinician, access_log: AccessLog
    ) -> None:
        self._registry = registry
        self._access_log = access_log
        self.clinician = clinician

    async def require(self, patient_id: UUID, *, recording: AccessAction | None = None) -> Patient:
        """Пациент врача по идентификатору из пути; чужой или несуществующий — 404.

        `recording` — записать действие в журнал доступа (ТР-11, п. 6); фиксирует сценарий.
        """
        patient = await self._registry.by_id(PatientId(patient_id))
        if patient is None or not patient.is_attended_by(self.clinician.id):
            raise ProblemError(status=404, code="patient_not_found", title="Пациент не найден")
        if recording is not None:
            await self._access_log.record(self.clinician.id, recording, patient.id)
        return patient

    async def all(self, *, search: str | None = None) -> list[Patient]:
        """Пациенты врача в порядке регистрации; `search` — подстрока ФИО (ТР-06)."""
        patients = await self._registry.patients_of(self.clinician.id)
        if not search or not search.strip():
            return patients
        return [patient for patient in patients if patient.name.matches(search)]

    async def record(self, action: AccessAction, patient_id: PatientId) -> None:
        """Записать действие врача с пациентом в журнал доступа; фиксирует сценарий."""
        await self._access_log.record(self.clinician.id, action, patient_id)
