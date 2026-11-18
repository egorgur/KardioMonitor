"""Порт: журнал оповещений пациентов."""

from collections.abc import Collection
from dataclasses import dataclass
from datetime import date, datetime
from typing import Protocol

from kardiomonitor.features.alerts.domain.patient_alert import AlertId, AlertKind, PatientAlert
from kardiomonitor.features.patients.domain.patient import PatientId


@dataclass(frozen=True, slots=True)
class FeedEntry:
    """Оповещение в ленте телефона. `position` растёт с каждой записью — курсор `after`."""

    position: int
    alert: PatientAlert


class AlertJournal(Protocol):
    """Все оповещения. Одно дневное оповещение на (пациент, вид, день) и одно «высокий пульс»
    на (пациент, начало эпизода) — повтор, перезапуск и второй процесс дубликата не создают."""

    async def save(self, alert: PatientAlert) -> None:
        """Новое или изменённое оповещение; встаёт в конец ленты телефона."""
        ...

    async def high_heart_rate_between(
        self, patient_id: PatientId, start: datetime, end: datetime
    ) -> list[PatientAlert]:
        """Оповещения «высокий пульс», эпизод которых пересекает [start, end]."""
        ...

    async def has_daily_alerts(self, patient_id: PatientId, day: date) -> bool:
        """Дневная проверка этого дня пациента уже создала оповещения."""
        ...

    async def has_kind_on(self, patient_id: PatientId, kind: AlertKind, day: date) -> bool:
        """У пациента уже есть неизменяемый снимок этого вида за день."""
        ...

    async def latest(
        self, patient_ids: Collection[PatientId], *, kind: AlertKind | None, limit: int
    ) -> list[PatientAlert]:
        """Оповещения пациентов, новые сверху; `kind` — только этого вида."""
        ...

    async def mark_viewed(self, patient_id: PatientId, alert_id: AlertId, at: datetime) -> bool:
        """Отметка врача «просмотрено»; повторная не меняет время. False — у пациента нет
        такого оповещения."""
        ...

    async def unviewed_counts(self, patient_ids: Collection[PatientId]) -> dict[PatientId, int]:
        """Непросмотренных оповещений у каждого пациента; без непросмотренных — 0."""
        ...

    async def feed(
        self,
        patient_id: PatientId,
        *,
        after: int,
        limit: int,
        kinds: Collection[AlertKind] | None = None,
    ) -> list[FeedEntry]:
        """Оповещения пациента с позицией больше `after`, по возрастанию позиции."""
        ...
