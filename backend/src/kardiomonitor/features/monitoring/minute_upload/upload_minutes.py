"""Сценарий: принять пакет минут пациента."""

from dataclasses import dataclass
from datetime import datetime

from kardiomonitor.features.monitoring.domain.activity_history import ActivityHistory
from kardiomonitor.features.monitoring.domain.minute_batch import MinuteBatch
from kardiomonitor.features.patients.domain.patient import PatientId
from kardiomonitor.shared_kernel import Transaction


@dataclass(frozen=True, slots=True)
class UploadReceipt:
    """Квитанция для телефона: сколько принято, сколько новых, последняя минута пациента."""

    received: int
    new: int
    last_minute_started_at: datetime


async def upload_minutes(
    patient_id: PatientId,
    batch: MinuteBatch,
    *,
    history: ActivityHistory,
    transaction: Transaction,
) -> UploadReceipt:
    """Идемпотентно: повтор пакета ничего не меняет, повтор минуты перезаписывает её.

    Фиксация — до ответа (ADR-0005): телефон, получивший 200, не присылает пакет повторно.
    """
    outcome = await history.save(patient_id, batch)
    last_minute = await history.last_minute_of(patient_id) or batch.last_started_at
    await transaction.commit()
    return UploadReceipt(len(batch.minutes), outcome.new_minutes, last_minute)
