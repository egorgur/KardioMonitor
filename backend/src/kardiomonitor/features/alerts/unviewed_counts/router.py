"""Точка входа слайса «Непросмотренные оповещения»."""

from uuid import UUID

from dishka.integrations.fastapi import DishkaRoute, FromDishka
from fastapi import APIRouter, Depends

from kardiomonitor.features.access.api import session_scheme
from kardiomonitor.features.alerts.domain.alert_journal import AlertJournal
from kardiomonitor.features.patients.api import AttendedPatients
from kardiomonitor.platform.web.api_model import ApiModel

router = APIRouter(
    prefix="/api/v1",
    tags=["Панель клиники"],
    route_class=DishkaRoute,
    dependencies=[Depends(session_scheme)],
)


class UnviewedCountOut(ApiModel):
    patient_id: UUID
    unviewed_alerts: int


@router.get("/alerts/unviewed-counts", summary="Непросмотренные оповещения пациентов врача")
async def list_unviewed_counts(
    patients: FromDishka[AttendedPatients],
    journal: FromDishka[AlertJournal],
) -> list[UnviewedCountOut]:
    """Строка на каждого пациента врача; панель соединяет с `GET /patients` по `patient_id`."""
    patient_ids = [patient.id for patient in await patients.all()]
    counts = await journal.unviewed_counts(patient_ids)
    return [
        UnviewedCountOut(patient_id=patient_id, unviewed_alerts=counts[patient_id])
        for patient_id in patient_ids
    ]
