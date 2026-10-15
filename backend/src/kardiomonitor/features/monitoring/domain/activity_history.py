"""Порт: история активности пациентов."""

from dataclasses import dataclass
from datetime import datetime
from typing import Protocol

from kardiomonitor.features.monitoring.domain.activity_minute import ActivityMinute
from kardiomonitor.features.monitoring.domain.minute_batch import MinuteBatch
from kardiomonitor.features.patients.domain.patient import PatientId


@dataclass(frozen=True, slots=True)
class SaveOutcome:
    """Сколько минут пакета раньше не было в истории."""

    new_minutes: int


class ActivityHistory(Protocol):
    """Все принятые минуты. Одна запись на (пациент, минута): повтор перезаписывает минуту."""

    async def save(self, patient_id: PatientId, batch: MinuteBatch) -> SaveOutcome: ...

    async def last_minute_of(self, patient_id: PatientId) -> datetime | None:
        """Начало самой поздней минуты пациента; None — минут ещё нет."""
        ...

    async def minutes_between(
        self, patient_id: PatientId, start: datetime, end: datetime
    ) -> list[ActivityMinute]:
        """Минуты пациента в [start, end), по порядку."""
        ...
