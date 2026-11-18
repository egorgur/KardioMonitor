"""Сценарий: принять пакет минут пациента."""

from dataclasses import dataclass
from datetime import datetime

from kardiomonitor.features.monitoring.domain.activity_history import ActivityHistory
from kardiomonitor.features.monitoring.domain.minute_batch import MinuteBatch
from kardiomonitor.features.monitoring.domain.monitoring_events import (
    MinutesReceived,
    MonitoringSubscriber,
)
from kardiomonitor.features.patients.domain.patient import Patient
from kardiomonitor.shared_kernel import Transaction


@dataclass(frozen=True, slots=True)
class UploadReceipt:
    """Квитанция для телефона: сколько принято, сколько новых, последняя минута пациента."""

    received: int
    new: int
    last_minute_started_at: datetime


async def upload_minutes(
    patient: Patient,
    batch: MinuteBatch,
    *,
    history: ActivityHistory,
    subscriber: MonitoringSubscriber,
    transaction: Transaction,
) -> UploadReceipt:
    """Идемпотентно: повтор пакета ничего не меняет, повтор минуты перезаписывает её.

    Подписчик — в той же транзакции (ADR-0009): оповещения и кеш итогов дня не расходятся с
    минутами. Фиксация — до ответа (ADR-0005): телефон, получивший 200, не повторяет пакет.
    """
    outcome = await history.save(patient.id, batch)
    await subscriber.minutes_received(MinutesReceived(patient, batch.minutes))
    last_minute = await history.last_minute_of(patient.id) or batch.last_started_at
    await transaction.commit()
    return UploadReceipt(len(batch.minutes), outcome.new_minutes, last_minute)
