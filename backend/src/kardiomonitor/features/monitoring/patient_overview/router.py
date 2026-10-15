"""Точка входа слайса «Пациенты на панели»."""

from uuid import UUID

from dishka.integrations.fastapi import DishkaRoute, FromDishka
from fastapi import APIRouter, Depends

from kardiomonitor.features.monitoring.domain.activity_history import ActivityHistory
from kardiomonitor.features.monitoring.domain.live_heart_rate import LiveHeartRateLog
from kardiomonitor.features.patients.domain.patient_registry import PatientRegistry
from kardiomonitor.platform.web.api_model import ApiModel, UtcDatetime
from kardiomonitor.platform.web.dashboard_access import require_dashboard_access

router = APIRouter(
    prefix="/api/v1",
    tags=["Панель клиники"],
    route_class=DishkaRoute,
    dependencies=[Depends(require_dashboard_access)],
)


class PatientOverviewOut(ApiModel):
    """Пациент и свежесть его данных."""

    patient_id: UUID
    name: str
    time_zone: str
    registered_at: UtcDatetime
    last_minute_started_at: UtcDatetime | None
    live_heart_rate: int | None
    live_heart_rate_measured_at: UtcDatetime | None


@router.get("/patients", summary="Все пациенты и свежесть их данных")
async def list_patients(
    registry: FromDishka[PatientRegistry],
    history: FromDishka[ActivityHistory],
    live_log: FromDishka[LiveHeartRateLog],
) -> list[PatientOverviewOut]:
    """Для демо пациентов единицы: два запроса на пациента допустимы."""
    overview = []
    for patient in await registry.all_patients():
        live = await live_log.latest_of(patient.id)
        overview.append(
            PatientOverviewOut(
                patient_id=patient.id,
                name=patient.name.value,
                time_zone=patient.time_zone.key,
                registered_at=patient.registered_at,
                last_minute_started_at=await history.last_minute_of(patient.id),
                live_heart_rate=None if live is None else live.heart_rate.bpm,
                live_heart_rate_measured_at=None if live is None else live.measured_at,
            )
        )
    return overview
