"""Фабрика приложения. Запуск: `uv run uvicorn kardiomonitor.app.main:create_app --factory`."""

import asyncio
import contextlib
import logging
from collections.abc import AsyncIterator, Callable
from contextlib import AbstractAsyncContextManager, asynccontextmanager
from datetime import timedelta
from functools import partial

from dishka import AsyncContainer, Provider, make_async_container
from dishka.integrations.fastapi import FastapiProvider, setup_dishka
from fastapi import FastAPI
from fastapi.staticfiles import StaticFiles
from sqlalchemy.ext.asyncio import AsyncEngine

from kardiomonitor.app.db_metadata import metadata
from kardiomonitor.app.errors import install_error_handlers
from kardiomonitor.app.provider import AppProvider, MonitoringReactionsProvider
from kardiomonitor.app.routers import include_slices
from kardiomonitor.app.schema_upgrade import require_current_schema
from kardiomonitor.app.settings import Settings
from kardiomonitor.features.access.provider import AccessProvider
from kardiomonitor.features.alerts.daily_check import run_daily_check
from kardiomonitor.features.alerts.provider import AlertsProvider
from kardiomonitor.features.monitoring.provider import MonitoringProvider
from kardiomonitor.features.patients.provider import PatientsProvider
from kardiomonitor.features.rehab.provider import RehabProvider
from kardiomonitor.platform.db.provider import DatabaseProvider
from kardiomonitor.platform.periodic import run_periodically
from kardiomonitor.platform.web.request_id import assign_request_id

logger = logging.getLogger(__name__)

DAILY_CHECK_INTERVAL = timedelta(minutes=1)
"""Время реакции дневной проверки — до минуты (ADR-0009)."""


def create_app(settings: Settings | None = None, *overrides: Provider) -> FastAPI:
    """Приложение с провайдерами dishka; `overrides` подменяют реализации (тесты)."""
    settings = settings or Settings()
    container = make_async_container(
        AppProvider(settings),
        DatabaseProvider(settings.database_url),
        AccessProvider(),
        PatientsProvider(),
        MonitoringProvider(),
        RehabProvider(),
        AlertsProvider(),
        MonitoringReactionsProvider(),
        FastapiProvider(),
        *overrides,
    )
    app = FastAPI(
        title="KardioMonitor",
        summary="Демо-сервер: пульс и шаги с браслета через телефон пациента",
        lifespan=_lifespan(container, periodic_checks=settings.periodic_checks),
    )
    app.middleware("http")(assign_request_id)
    install_error_handlers(app)
    include_slices(app)
    app.add_api_route(
        "/health", _health, methods=["GET"], tags=["Служебное"], summary="Процесс жив"
    )
    _mount_frontend(app, settings)
    setup_dishka(container=container, app=app)
    return app


def _lifespan(
    container: AsyncContainer, *, periodic_checks: bool
) -> Callable[[FastAPI], AbstractAsyncContextManager[None]]:
    @asynccontextmanager
    async def lifespan(_app: FastAPI) -> AsyncIterator[None]:
        try:
            # Новая схема создаётся при запуске; старая требует явной команды обновления.
            engine = await container.get(AsyncEngine)
            async with engine.begin() as connection:
                await connection.run_sync(require_current_schema)
                await connection.run_sync(metadata.create_all)
            async with _periodic_checks(container, is_enabled=periodic_checks):
                yield
        finally:
            await container.close()

    return lifespan


@asynccontextmanager
async def _periodic_checks(container: AsyncContainer, *, is_enabled: bool) -> AsyncIterator[None]:
    """Дневные оповещения раз в минуту, пока сервер работает (ADR-0009)."""
    if not is_enabled:
        yield
        return
    task = asyncio.create_task(
        run_periodically(
            partial(run_daily_check, container),
            interval=DAILY_CHECK_INTERVAL,
            name="дневные оповещения",
        )
    )
    try:
        yield
    finally:
        # Отмена — штатная остановка задачи при остановке сервера: дождаться её завершения.
        task.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await task


async def _health() -> dict[str, str]:
    return {"status": "ok"}


def _mount_frontend(app: FastAPI, settings: Settings) -> None:
    """Панель и API — с одного адреса: один туннель ngrok, без CORS."""
    if (settings.frontend_dist / "index.html").is_file():
        app.mount("/", StaticFiles(directory=settings.frontend_dist, html=True), name="frontend")
    else:
        logger.warning("панель не собрана (%s) — сервер отдаёт только API", settings.frontend_dist)
