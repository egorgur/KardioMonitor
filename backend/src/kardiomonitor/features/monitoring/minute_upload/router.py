"""Точка входа слайса «Приём минут»."""

from dishka.integrations.fastapi import DishkaRoute, FromDishka
from fastapi import APIRouter, Depends

from kardiomonitor.features.monitoring.domain.activity_history import ActivityHistory
from kardiomonitor.features.patients.api import CurrentPatient, bearer_scheme
from kardiomonitor.shared_kernel import Transaction

from .schemas import MinuteBatchIn, UploadReceiptOut
from .upload_minutes import upload_minutes

router = APIRouter(
    prefix="/api/v1",
    tags=["Телефон"],
    route_class=DishkaRoute,
    dependencies=[Depends(bearer_scheme)],
)


@router.post("/me/activity-minutes", summary="Принять минуты активности с телефона")
async def receive_activity_minutes(
    body: MinuteBatchIn,
    patient: FromDishka[CurrentPatient],
    history: FromDishka[ActivityHistory],
    transaction: FromDishka[Transaction],
) -> UploadReceiptOut:
    """Идемпотентно: телефон может повторять запрос после обрыва связи."""
    receipt = await upload_minutes(
        patient.id, body.to_domain(), history=history, transaction=transaction
    )
    return UploadReceiptOut.from_domain(receipt)
