"""Точка входа слайса «Создание пациента»."""

from uuid import UUID

from dishka.integrations.fastapi import DishkaRoute, FromDishka
from fastapi import APIRouter, Depends

from kardiomonitor.features.access.api import session_scheme
from kardiomonitor.features.access.domain.access_log import AccessAction
from kardiomonitor.features.patients.api import AttendedPatients
from kardiomonitor.features.patients.domain.enrollment_code import EnrollmentCodes
from kardiomonitor.features.patients.domain.patient import time_zone_of
from kardiomonitor.features.patients.domain.patient_registry import PatientRegistry
from kardiomonitor.shared_kernel import Clock, Transaction

from .admit_patient import Admission, admit_patient, reissue_code
from .schemas import AdmittedPatientOut, EnrollmentCodeOut, PatientAdmissionIn

router = APIRouter(
    prefix="/api/v1",
    tags=["Панель клиники"],
    route_class=DishkaRoute,
    dependencies=[Depends(session_scheme)],
)


@router.post("/patients", status_code=201, summary="Создать пациента")
async def create_patient(
    body: PatientAdmissionIn,
    patients: FromDishka[AttendedPatients],
    registry: FromDishka[PatientRegistry],
    codes: FromDishka[EnrollmentCodes],
    clock: FromDishka[Clock],
    transaction: FromDishka[Transaction],
) -> AdmittedPatientOut:
    """Врач становится лечащим врачом; код привязки — для QR v2, показывается один раз."""
    admission = Admission(body.to_profile(), time_zone_of(body.time_zone))
    patient, issued = await admit_patient(
        admission,
        patients=patients,
        registry=registry,
        codes=codes,
        clock=clock,
        transaction=transaction,
    )
    return AdmittedPatientOut.of(patient, issued)


@router.post(
    "/patients/{patient_id}/enrollment-code",
    status_code=201,
    summary="Новый код привязки телефона пациента",
)
async def create_enrollment_code(
    patient_id: UUID,
    patients: FromDishka[AttendedPatients],
    codes: FromDishka[EnrollmentCodes],
    clock: FromDishka[Clock],
    transaction: FromDishka[Transaction],
) -> EnrollmentCodeOut:
    """Прежний код перестаёт действовать. Показывается один раз."""
    patient = await patients.require(patient_id, recording=AccessAction.ISSUED_ENROLLMENT_CODE)
    issued = await reissue_code(patient.id, codes=codes, clock=clock, transaction=transaction)
    return EnrollmentCodeOut.from_domain(issued)
