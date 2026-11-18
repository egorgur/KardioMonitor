"""События приёма данных и порт подписчика: реакция верхних контекстов (ADR-0009).

Мониторинг не знает, кто реагирует: «высокий пульс» (Оповещения) и кеш итогов дня
(Реабилитация) реализуют `MonitoringSubscriber`, связывает их `app`. Подписчик вызывается в
транзакции приёма, до `commit`: ошибка подписчика отклоняет приём, телефон повторит.
"""

from collections.abc import Sequence
from dataclasses import dataclass
from typing import Protocol

from kardiomonitor.features.monitoring.domain.activity_minute import ActivityMinute
from kardiomonitor.features.monitoring.domain.live_heart_rate import LiveHeartRateReading
from kardiomonitor.features.patients.domain.patient import Patient


@dataclass(frozen=True, slots=True)
class MinutesReceived:
    """Пакет минут пациента сохранён в истории (ещё не зафиксирован)."""

    patient: Patient
    minutes: tuple[ActivityMinute, ...]


@dataclass(frozen=True, slots=True)
class LiveHeartRateReceived:
    """Значение «пульса сейчас» сохранено в журнале (ещё не зафиксировано)."""

    patient: Patient
    reading: LiveHeartRateReading


class MonitoringSubscriber(Protocol):
    """Реакция на принятые данные. Быстрая, без внешних сервисов, в транзакции приёма."""

    async def minutes_received(self, event: MinutesReceived) -> None: ...

    async def live_heart_rate_received(self, event: LiveHeartRateReceived) -> None: ...


@dataclass(frozen=True, slots=True)
class MonitoringSubscribers:
    """Несколько подписчиков как один, по порядку; без подписчиков — реакции нет."""

    subscribers: Sequence[MonitoringSubscriber] = ()

    async def minutes_received(self, event: MinutesReceived) -> None:
        for subscriber in self.subscribers:
            await subscriber.minutes_received(event)

    async def live_heart_rate_received(self, event: LiveHeartRateReceived) -> None:
        for subscriber in self.subscribers:
            await subscriber.live_heart_rate_received(event)
