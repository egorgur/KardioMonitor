"""Настоящая SQLite во временном файле: схема создаётся так же, как при запуске сервера."""

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from pathlib import Path

from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from kardiomonitor.app.db_metadata import metadata


def database_url(directory: Path) -> str:
    return f"sqlite+aiosqlite:///{directory / 'kardio.db'}"


@asynccontextmanager
async def database_session(directory: Path) -> AsyncIterator[AsyncSession]:
    """Сессия к новой базе со всеми таблицами; база удаляется вместе с каталогом теста."""
    engine = create_async_engine(database_url(directory))
    async with engine.begin() as connection:
        await connection.run_sync(metadata.create_all)
    async with async_sessionmaker(engine, expire_on_commit=False)() as session:
        yield session
    await engine.dispose()
