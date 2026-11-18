"""Точка входа слайса «Оповещения на телефон»."""

from typing import Annotated

from dishka.integrations.fastapi import DishkaRoute, FromDishka
from fastapi import APIRouter, Depends, Query

from kardiomonitor.features.alerts.domain.alert_journal import AlertJournal
from kardiomonitor.features.alerts.domain.patient_alert import AlertKind
from kardiomonitor.features.patients.api import CurrentPatient, bearer_scheme

from .schemas import AlertFeedOut

PAGE_SIZE = 100

router = APIRouter(
    prefix="/api/v1",
    tags=["Телефон"],
    route_class=DishkaRoute,
    dependencies=[Depends(bearer_scheme)],
)


@router.get("/me/alerts", summary="Новые оповещения пациента")
async def get_my_alerts(
    patient: FromDishka[CurrentPatient],
    journal: FromDishka[AlertJournal],
    after: Annotated[int, Query(ge=0, description="`cursor` прошлого ответа; 0 — с начала")] = 0,
    supported_kinds: Annotated[
        list[AlertKind] | None,
        Query(description="Известные телефону виды (повторяемый); без него — четыре прежних вида"),
    ] = None,
) -> AlertFeedOut:
    """Телефон забирает при синхронизации; не больше 100 за раз (`has_more`)."""
    kinds = (
        supported_kinds
        if supported_kinds is not None
        else [kind for kind in AlertKind if kind is not AlertKind.TRAINING_MINUTES_EXCEEDED]
    )
    entries = await journal.feed(patient.id, after=after, limit=PAGE_SIZE + 1, kinds=kinds)
    return AlertFeedOut.from_domain(entries, after=after, limit=PAGE_SIZE)
