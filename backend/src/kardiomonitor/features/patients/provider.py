"""Провайдер dishka контекста «Пациенты»: реализации портов и текущий пациент (ADR-0003)."""

from dishka import Provider, Scope, provide
from fastapi import Request

from kardiomonitor.features.patients.api import CurrentPatient, NotAuthenticatedError
from kardiomonitor.features.patients.data.sql_patient_registry import SqlPatientRegistry
from kardiomonitor.features.patients.domain.patient_registry import PatientRegistry
from kardiomonitor.features.patients.domain.upload_token import UploadToken

_BEARER_PREFIX = "bearer "


class PatientsProvider(Provider):
    """Реализации портов контекста — только здесь знают о `data/`."""

    scope = Scope.REQUEST

    registry = provide(SqlPatientRegistry, provides=PatientRegistry)

    @provide
    async def current_patient(self, request: Request, registry: PatientRegistry) -> CurrentPatient:
        token = _bearer_token(request)
        patient = None if token is None else await registry.by_token(token)
        if patient is None:
            raise NotAuthenticatedError
        return CurrentPatient(patient)


def _bearer_token(request: Request) -> UploadToken | None:
    header = request.headers.get("Authorization", "")
    if not header.lower().startswith(_BEARER_PREFIX):
        return None
    value = header[len(_BEARER_PREFIX) :].strip()
    return UploadToken(value) if value else None
