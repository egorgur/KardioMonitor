"""Явное идемпотентное обновление SQLite без удаления данных.

Остановить сервер, сделать резервную копию базы вместе с WAL и выполнить:
`uv run python -m kardiomonitor.app.schema_upgrade` (KARDIO_DATABASE_URL из настроек).
"""

import asyncio

from sqlalchemy import Connection, inspect, text

from kardiomonitor.app.settings import Settings
from kardiomonitor.platform.db.provider import create_engine

_TABLE = "rehab_plan_revisions"
_COLUMN = "training_minutes_upper_threshold"


def _needs_upgrade(connection: Connection) -> bool:
    inspector = inspect(connection)
    if not inspector.has_table(_TABLE):
        return False
    return _COLUMN not in {column["name"] for column in inspector.get_columns(_TABLE)}


def require_current_schema(connection: Connection) -> None:
    """Запуск не изменяет старую схему молча: требуется явная команда администратора."""
    if _needs_upgrade(connection):
        raise RuntimeError(
            "схема базы устарела: остановите сервер, сохраните резервную копию и выполните "
            "uv run python -m kardiomonitor.app.schema_upgrade"
        )


def upgrade_schema(connection: Connection) -> None:
    """Добавить nullable-порог: существующие версии остаются отключёнными."""
    if _needs_upgrade(connection):
        connection.execute(
            text(
                "ALTER TABLE rehab_plan_revisions "
                "ADD COLUMN training_minutes_upper_threshold SMALLINT "
                "CHECK (training_minutes_upper_threshold BETWEEN 1 AND 1440)"
            )
        )


async def main() -> None:
    engine = create_engine(Settings().database_url)
    try:
        async with engine.begin() as connection:
            await connection.run_sync(upgrade_schema)
    finally:
        await engine.dispose()


if __name__ == "__main__":
    asyncio.run(main())
