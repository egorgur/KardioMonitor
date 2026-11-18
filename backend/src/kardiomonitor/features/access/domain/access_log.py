"""Журнал доступа к данным пациентов (ТР-11, п. 6): кто, что, к чему, когда — без ФИО и диагноза."""

from enum import StrEnum
from typing import Protocol
from uuid import UUID

from kardiomonitor.features.access.domain.clinician import ClinicianId


class AccessAction(StrEnum):
    CREATED_PATIENT = "created_patient"
    VIEWED_PATIENT_CARD = "viewed_patient_card"
    ISSUED_ENROLLMENT_CODE = "issued_enrollment_code"
    VIEWED_REHAB_PLAN = "viewed_rehab_plan"
    CHANGED_REHAB_PLAN = "changed_rehab_plan"


class AccessLog(Protocol):
    """Записи журнала — в транзакции сценария; объект — идентификатор без типа («Доступ» не
    знает пациентов, ADR-0008)."""

    async def record(
        self, clinician_id: ClinicianId, action: AccessAction, subject_id: UUID
    ) -> None:
        """Записать действие врача с текущим моментом."""
        ...
