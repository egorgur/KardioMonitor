"""Периодическая задача (ADR-0009): ошибка запуска не останавливает цикл."""

import asyncio
from datetime import timedelta

import pytest

from kardiomonitor.platform.periodic import run_periodically


async def test_ошибка_одного_запуска_не_останавливает_следующие(
    caplog: pytest.LogCaptureFixture,
) -> None:
    runs: list[int] = []
    third_run = asyncio.Event()

    async def job() -> None:
        runs.append(len(runs) + 1)
        if len(runs) == 1:
            raise RuntimeError("сбой базы")
        if len(runs) == 3:
            third_run.set()

    task = asyncio.create_task(run_periodically(job, interval=timedelta(0), name="проверка"))
    await asyncio.wait_for(third_run.wait(), timeout=1)
    task.cancel()

    assert runs[:3] == [1, 2, 3]
    assert "периодическая задача проверка завершилась ошибкой" in caplog.text
