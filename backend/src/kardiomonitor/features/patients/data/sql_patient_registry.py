"""Реестр пациентов в базе."""

from uuid import UUID
from zoneinfo import ZoneInfo

from sqlalchemy import Select, select, update
from sqlalchemy.dialects.sqlite import insert
from sqlalchemy.ext.asyncio import AsyncSession

from kardiomonitor.features.access.domain.clinician import ClinicianId
from kardiomonitor.features.patients.data.tables import (
    AttendingClinicianRow,
    PatientProfileRow,
    PatientRow,
)
from kardiomonitor.features.patients.domain.patient import Patient, PatientId, PatientName
from kardiomonitor.features.patients.domain.patient_profile import (
    BodyParameters,
    FullName,
    PatientProfile,
)
from kardiomonitor.features.patients.domain.upload_token import UploadToken

_PATIENTS = (
    select(PatientRow, AttendingClinicianRow.clinician_id)
    .outerjoin(AttendingClinicianRow, AttendingClinicianRow.patient_id == PatientRow.id)
    .order_by(PatientRow.registered_at)
)


class SqlPatientRegistry:
    """Реализация `PatientRegistry`. Транзакцию фиксирует сценарий, а не реестр (ADR-0005)."""

    def __init__(self, session: AsyncSession) -> None:
        self._session = session

    async def add(self, patient: Patient, token: UploadToken) -> None:
        self._session.add(
            PatientRow(
                id=patient.id,
                name=patient.name.value,
                time_zone=patient.time_zone.key,
                registered_at=patient.registered_at,
                token_fingerprint=token.fingerprint,
            )
        )
        await self._session.flush()
        await self.save_attending_clinician(patient)

    async def by_token(self, token: UploadToken) -> Patient | None:
        found = await self._patients(
            _PATIENTS.where(PatientRow.token_fingerprint == token.fingerprint)
        )
        return found[0] if found else None

    async def by_id(self, patient_id: PatientId) -> Patient | None:
        found = await self._patients(_PATIENTS.where(PatientRow.id == patient_id))
        return found[0] if found else None

    async def all_patients(self) -> list[Patient]:
        return await self._patients(_PATIENTS)

    async def patients_of(self, clinician_id: ClinicianId) -> list[Patient]:
        return await self._patients(
            _PATIENTS.where(AttendingClinicianRow.clinician_id == clinician_id)
        )

    async def save_attending_clinician(self, patient: Patient) -> None:
        if patient.attending_clinician_id is None:
            return
        statement = insert(AttendingClinicianRow).values(
            patient_id=patient.id, clinician_id=patient.attending_clinician_id
        )
        await self._session.execute(
            statement.on_conflict_do_update(
                index_elements=[AttendingClinicianRow.patient_id],
                set_={"clinician_id": statement.excluded.clinician_id},
            )
        )

    async def admit(self, patient: Patient, profile: PatientProfile) -> None:
        # Токен-заглушка: колонка обязательна (таблица без миграций, ADR-0007), значение никому
        # не выдаётся — телефон получит свой токен при привязке.
        await self.add(patient, UploadToken.issue())
        name, body = profile.full_name, profile.body
        self._session.add(
            PatientProfileRow(
                patient_id=patient.id,
                last_name=name.last_name,
                first_name=name.first_name,
                middle_name=name.middle_name,
                birth_date=profile.birth_date,
                height_cm=body.height_cm,
                weight_kg=body.weight_kg,
                diagnosis=body.diagnosis,
            )
        )
        await self._session.flush()

    async def profile_of(self, patient_id: PatientId) -> PatientProfile | None:
        row = await self._session.get(PatientProfileRow, patient_id)
        if row is None:
            return None
        return PatientProfile(
            FullName(row.last_name, row.first_name, row.middle_name),
            row.birth_date,
            BodyParameters(row.height_cm, row.weight_kg, row.diagnosis),
        )

    async def replace_token(self, patient_id: PatientId, token: UploadToken) -> None:
        await self._session.execute(
            update(PatientRow)
            .where(PatientRow.id == patient_id)
            .values(token_fingerprint=token.fingerprint)
        )

    async def _patients(self, query: Select[PatientRow, UUID]) -> list[Patient]:
        """Лечащего врача может не быть (outer join): `clinician_id` тогда None."""
        rows = await self._session.execute(query)
        return [_to_patient(row, clinician_id) for row, clinician_id in rows]


def _to_patient(row: PatientRow, clinician_id: UUID | None) -> Patient:
    return Patient(
        id=PatientId(row.id),
        name=PatientName(row.name),
        time_zone=ZoneInfo(row.time_zone),
        registered_at=row.registered_at,
        attending_clinician_id=None if clinician_id is None else ClinicianId(clinician_id),
    )
