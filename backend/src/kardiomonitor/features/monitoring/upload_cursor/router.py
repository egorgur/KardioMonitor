"""Точка входа слайса «Откуда продолжать»."""

from dishka.integrations.fastapi import DishkaRoute, FromDishka
from fastapi import APIRouter, Depends

from kardiomonitor.features.monitoring.domain.activity_history import ActivityHistory
from kardiomonitor.features.patients.api import CurrentPatient, bearer_scheme
from kardiomonitor.platform.web.api_model import ApiModel, UtcDatetime

router = APIRouter(
    prefix="/api/v1",
    tags=["Телефон"],
    route_class=DishkaRoute,
    dependencies=[Depends(bearer_scheme)],
)


class UploadCursorOut(ApiModel):
    """Начало последней принятой минуты; null — минут пациента ещё нет."""

    started_at: UtcDatetime | None


@router.get("/me/activity-minutes/last", summary="Последняя принятая минута пациента")
async def get_upload_cursor(
    patient: FromDishka[CurrentPatient], history: FromDishka[ActivityHistory]
) -> UploadCursorOut:
    """Телефон отправляет минуты начиная со следующей после этой."""
    return UploadCursorOut(started_at=await history.last_minute_of(patient.id))
