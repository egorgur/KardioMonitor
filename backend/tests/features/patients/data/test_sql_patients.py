"""Пациенты от клиники и коды привязки на настоящей SQLite. Данные вымышленные."""

from collections.abc import AsyncIterator
from datetime import date, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

import pytest
from sqlalchemy.ext.asyncio import AsyncSession

from kardiomonitor.features.access.data.sql_clinicians import SqlClinicians
from kardiomonitor.features.patients.data.sql_enrollment_codes import SqlEnrollmentCodes
from kardiomonitor.features.patients.data.sql_patient_registry import SqlPatientRegistry
from kardiomonitor.features.patients.domain.enrollment_code import EnrollmentCode
from kardiomonitor.features.patients.domain.patient import Patient
from kardiomonitor.features.patients.domain.patient_profile import (
    BodyParameters,
    FullName,
    PatientProfile,
)
from kardiomonitor.features.patients.domain.upload_token import UploadToken
from tests.database import database_session
from tests.fakes import NOW
from tests.features.access.clinicians import DOCTOR_A

PROFILE = PatientProfile(
    FullName("Тестов", "Пётр", None), date(1961, 4, 12), BodyParameters(176, 82.5, "текст")
)


@pytest.fixture
async def session(tmp_path: Path) -> AsyncIterator[AsyncSession]:
    async with database_session(tmp_path) as session:
        await SqlClinicians(session).add(DOCTOR_A)
        yield session


@pytest.fixture
async def patient(session: AsyncSession) -> Patient:
    patient = Patient.admit(PROFILE, ZoneInfo("Europe/Moscow"), DOCTOR_A.id, now=NOW)
    await SqlPatientRegistry(session).admit(patient, PROFILE)
    return patient


async def test_пациент_от_клиники_читается_с_карточкой_и_врачом(
    session: AsyncSession, patient: Patient
) -> None:
    registry = SqlPatientRegistry(session)

    assert await registry.by_id(patient.id) == patient
    assert await registry.profile_of(patient.id) == PROFILE
    assert [p.id for p in await registry.patients_of(DOCTOR_A.id)] == [patient.id]


async def test_код_привязки_гасится_один_раз(session: AsyncSession, patient: Patient) -> None:
    codes, code = SqlEnrollmentCodes(session), EnrollmentCode.issue()
    await codes.issue(patient.id, code, NOW + timedelta(hours=72))

    assert await codes.redeem(code, NOW) == patient.id
    assert await codes.redeem(code, NOW) is None


async def test_истёкший_код_не_гасится(session: AsyncSession, patient: Patient) -> None:
    codes, code = SqlEnrollmentCodes(session), EnrollmentCode.issue()
    await codes.issue(patient.id, code, NOW + timedelta(hours=72))

    assert await codes.redeem(code, NOW + timedelta(hours=72)) is None


async def test_новый_код_отменяет_прежний(session: AsyncSession, patient: Patient) -> None:
    codes, old, new = SqlEnrollmentCodes(session), EnrollmentCode.issue(), EnrollmentCode.issue()
    await codes.issue(patient.id, old, NOW + timedelta(hours=72))
    await codes.issue(patient.id, new, NOW + timedelta(hours=72))

    assert await codes.redeem(old, NOW) is None
    assert await codes.redeem(new, NOW) == patient.id


async def test_новый_токен_заменяет_прежний(session: AsyncSession, patient: Patient) -> None:
    registry, token = SqlPatientRegistry(session), UploadToken.issue()

    await registry.replace_token(patient.id, token)

    assert await registry.by_token(token) == patient
