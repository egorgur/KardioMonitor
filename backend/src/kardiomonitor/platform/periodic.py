"""Периодические задачи в процессе сервера (ADR-0009): без очереди и отдельного воркера."""

import asyncio
import logging
from collections.abc import Awaitable, Callable
from datetime import timedelta

logger = logging.getLogger(__name__)


async def run_periodically(
    job: Callable[[], Awaitable[object]], *, interval: timedelta, name: str
) -> None:
    """Запускать `job` сразу и затем раз в `interval`, пока задачу не отменят.

    Ошибка запуска пишется в журнал и не останавливает цикл: следующий запуск повторит работу
    (задачи идемпотентны). Сообщение — без данных пациента: только имя задачи и трассировка.
    """
    while True:
        try:
            await job()
        except Exception:
            logger.exception("периодическая задача %s завершилась ошибкой", name)
        await asyncio.sleep(interval.total_seconds())
