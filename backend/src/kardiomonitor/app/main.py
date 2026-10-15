"""Фабрика приложения. Запуск: `uv run uvicorn kardiomonitor.app.main:create_app --factory`."""

import logging
from collections.abc import AsyncIterator, Callable
from contextlib import AbstractAsyncContextManager, asynccontextmanager

from dishka import AsyncContainer, Provider, make_async_container
from dishka.integrations.fastapi import FastapiProvider, setup_dishka
from fastapi import FastAPI
from fastapi.staticfiles import StaticFiles
from sqlalchemy.ext.asyncio import AsyncEngine

from kardiomonitor.app.db_metadata import metadata
from kardiomonitor.app.errors import install_error_handlers
from kardiomonitor.app.provider import AppProvider
from kardiomonitor.app.routers import include_slices
from kardiomonitor.app.settings import Settings
from kardiomonitor.features.monitoring.provider import MonitoringProvider
from kardiomonitor.features.patients.provider import PatientsProvider
from kardiomonitor.platform.db.provider import DatabaseProvider
from kardiomonitor.platform.web.request_id import assign_request_id

logger = logging.getLogger(__name__)


def create_app(settings: Settings | None = None, *overrides: Provider) -> FastAPI:
    """Приложение с провайдерами dishka; `overrides` подменяют реализации (тесты)."""
    settings = settings or Settings()
    container = make_async_container(
        AppProvider(settings),
        DatabaseProvider(settings.database_url),
        PatientsProvider(),
        MonitoringProvider(),
        FastapiProvider(),
        *overrides,
    )
    app = FastAPI(
        title="KardioMonitor",
        summary="Демо-сервер: пульс и шаги с браслета через телефон пациента",
        lifespan=_lifespan(container),
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
    container: AsyncContainer,
) -> Callable[[FastAPI], AbstractAsyncContextManager[None]]:
    @asynccontextmanager
    async def lifespan(_app: FastAPI) -> AsyncIterator[None]:
        # Демо-прототип на SQLite: схема создаётся при запуске, без Alembic (ADR-0007).
        engine = await container.get(AsyncEngine)
        async with engine.begin() as connection:
            await connection.run_sync(metadata.create_all)
        yield
        await container.close()

    return lifespan


async def _health() -> dict[str, str]:
    return {"status": "ok"}


def _mount_frontend(app: FastAPI, settings: Settings) -> None:
    """Панель и API — с одного адреса: один туннель ngrok, без CORS."""
    if (settings.frontend_dist / "index.html").is_file():
        app.mount("/", StaticFiles(directory=settings.frontend_dist, html=True), name="frontend")
    else:
        logger.warning("панель не собрана (%s) — сервер отдаёт только API", settings.frontend_dist)
