"""Точки входа слайса «Оповещения врачу»."""

from typing import Annotated
from uuid import UUID

from dishka.integrations.fastapi import DishkaRoute, FromDishka
from fastapi import APIRouter, Depends, Query

from kardiomonitor.features.access.api import session_scheme
from kardiomonitor.features.alerts.domain.alert_journal import AlertJournal
from kardiomonitor.features.alerts.domain.patient_alert import AlertId, AlertKind
from kardiomonitor.features.patients.api import AttendedPatients
from kardiomonitor.platform.web.problem import ProblemError
from kardiomonitor.shared_kernel import Clock, Transaction

from .mark_viewed import mark_viewed
from .schemas import ClinicianAlertOut

MAX_LIMIT = 500

router = APIRouter(
    prefix="/api/v1",
    tags=["Панель клиники"],
    route_class=DishkaRoute,
    dependencies=[Depends(session_scheme)],
)

KindQuery = Annotated[AlertKind | None, Query(description="Только оповещения этого вида")]
LimitQuery = Annotated[int, Query(ge=1, le=MAX_LIMIT, description="Сколько последних")]


@router.get("/alerts", summary="Оповещения всех пациентов врача")
async def list_alerts(
    patients: FromDishka[AttendedPatients],
    journal: FromDishka[AlertJournal],
    kind: KindQuery = None,
    limit: LimitQuery = 100,
) -> list[ClinicianAlertOut]:
    """Новые сверху. Оповещения приходят с задержкой синхронизации телефона (до 30–60 мин)."""
    patient_ids = [patient.id for patient in await patients.all()]
    alerts = await journal.latest(patient_ids, kind=kind, limit=limit)
    return [ClinicianAlertOut.from_domain(alert) for alert in alerts]


@router.get("/patients/{patient_id}/alerts", summary="Оповещения пациента")
async def list_patient_alerts(
    patient_id: UUID,
    patients: FromDishka[AttendedPatients],
    journal: FromDishka[AlertJournal],
    kind: KindQuery = None,
    limit: LimitQuery = 100,
) -> list[ClinicianAlertOut]:
    """Новые сверху; для карточки пациента."""
    patient = await patients.require(patient_id)
    alerts = await journal.latest([patient.id], kind=kind, limit=limit)
    return [ClinicianAlertOut.from_domain(alert) for alert in alerts]


@router.post(
    "/patients/{patient_id}/alerts/{alert_id}/viewed",
    status_code=204,
    summary="Отметить оповещение «просмотрено»",
)
async def mark_alert_viewed(
    patient_id: UUID,
    alert_id: UUID,
    patients: FromDishka[AttendedPatients],
    journal: FromDishka[AlertJournal],
    clock: FromDishka[Clock],
    transaction: FromDishka[Transaction],
) -> None:
    """Идемпотентно. Оповещения не удаляются: «просмотрено» только убирает его из счётчика."""
    patient = await patients.require(patient_id)
    found = await mark_viewed(
        patient, AlertId(alert_id), journal=journal, clock=clock, transaction=transaction
    )
    if not found:
        raise ProblemError(status=404, code="alert_not_found", title="Оповещение не найдено")
