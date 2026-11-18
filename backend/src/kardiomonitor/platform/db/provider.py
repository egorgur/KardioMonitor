"""Провайдер dishka: engine на всё приложение, сессия и транзакция на запрос (ADR-0005)."""

import logging
from collections.abc import AsyncIterator

from dishka import Provider, Scope, provide
from sqlalchemy import event
from sqlalchemy.engine.interfaces import DBAPIConnection
from sqlalchemy.ext.asyncio import (
    AsyncEngine,
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)
from sqlalchemy.pool import ConnectionPoolEntry

from kardiomonitor.shared_kernel import Transaction


class DatabaseProvider(Provider):
    """Подключение к базе по адресу из настроек."""

    def __init__(self, database_url: str) -> None:
        super().__init__()
        self._database_url = database_url

    @provide(scope=Scope.APP)
    async def engine(self) -> AsyncIterator[AsyncEngine]:
        engine = create_engine(self._database_url)
        if engine.dialect.name == "sqlite":
            event.listen(engine.sync_engine, "connect", _configure_sqlite)
        yield engine
        await engine.dispose()

    @provide(scope=Scope.APP)
    def sessionmaker(self, engine: AsyncEngine) -> async_sessionmaker[AsyncSession]:
        return async_sessionmaker(engine, expire_on_commit=False)

    @provide(scope=Scope.REQUEST)
    async def session(
        self, sessionmaker: async_sessionmaker[AsyncSession]
    ) -> AsyncIterator[AsyncSession]:
        # Закрытие сессии откатывает всё, что сценарий не зафиксировал явно.
        async with sessionmaker() as session:
            yield session

    @provide(scope=Scope.REQUEST)
    def transaction(self, session: AsyncSession) -> Transaction:
        return session


def _configure_sqlite(connection: DBAPIConnection, _record: ConnectionPoolEntry) -> None:
    """Внешние ключи SQLite выключены по умолчанию; WAL — чтение не ждёт записи."""
    cursor = connection.cursor()
    cursor.execute("PRAGMA foreign_keys=ON")
    cursor.execute("PRAGMA journal_mode=WAL")
    cursor.close()


def create_engine(database_url: str) -> AsyncEngine:
    """Engine, который не выдаёт параметры запросов — ФИО, диагноз, хеши паролей — ни в журнал,
    ни в текст ошибок базы, при любом уровне журнала (ТР-07, п. 5; coding-rules §13).

    `hide_parameters` — для журнала SQLAlchemy и исключений; aiosqlite на уровне DEBUG пишет
    запросы с параметрами сам.
    """
    logging.getLogger("aiosqlite").setLevel(logging.INFO)
    return create_async_engine(database_url, hide_parameters=True)
