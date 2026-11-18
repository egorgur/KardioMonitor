"""Точки входа слайса «Пульс сейчас»: приём с телефона и чтение для панели."""

from datetime import timedelta
from typing import Annotated
from uuid import UUID

from dishka.integrations.fastapi import DishkaRoute, FromDishka
from fastapi import APIRouter, Depends, Query

from kardiomonitor.features.access.api import session_scheme
from kardiomonitor.features.monitoring.domain.live_heart_rate import LiveHeartRateLog
from kardiomonitor.features.monitoring.domain.monitoring_events import MonitoringSubscriber
from kardiomonitor.features.patients.api import AttendedPatients, CurrentPatient, bearer_scheme
from kardiomonitor.shared_kernel import Clock, Transaction

from .record_live_heart_rate import record_live_heart_rate
from .schemas import LiveHeartRateIn, LiveHeartRateWindowOut

MAX_WINDOW_SECONDS = 3600

router = APIRouter(prefix="/api/v1", route_class=DishkaRoute)


@router.post(
    "/me/live-heart-rate",
    status_code=204,
    tags=["Телефон"],
    summary="Принять значение «пульса сейчас»",
    dependencies=[Depends(bearer_scheme)],
)
async def receive_live_heart_rate(
    body: LiveHeartRateIn,
    patient: FromDishka[CurrentPatient],
    log: FromDishka[LiveHeartRateLog],
    subscriber: FromDishka[MonitoringSubscriber],
    transaction: FromDishka[Transaction],
) -> None:
    """Идемпотентно по `measured_at`."""
    await record_live_heart_rate(
        patient, body.to_domain(), log=log, subscriber=subscriber, transaction=transaction
    )


@router.get(
    "/patients/{patient_id}/live-heart-rate",
    tags=["Панель клиники"],
    summary="Пульс сейчас за последние секунды",
    dependencies=[Depends(session_scheme)],
)
async def get_live_heart_rate(
    patient_id: UUID,
    patients: FromDishka[AttendedPatients],
    log: FromDishka[LiveHeartRateLog],
    clock: FromDishka[Clock],
    window_seconds: Annotated[int, Query(ge=10, le=MAX_WINDOW_SECONDS)] = 300,
) -> LiveHeartRateWindowOut:
    """Панель опрашивает этот эндпоинт каждые несколько секунд."""
    patient = await patients.require(patient_id)
    since = clock.now() - timedelta(seconds=window_seconds)
    readings = await log.readings_since(patient.id, since)
    return LiveHeartRateWindowOut.from_domain(window_seconds, readings)
