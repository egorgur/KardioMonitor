"""Общая база таблиц и тип колонки времени."""

from datetime import UTC, datetime

from sqlalchemy import DateTime, MetaData
from sqlalchemy.engine import Dialect
from sqlalchemy.orm import DeclarativeBase
from sqlalchemy.types import TypeDecorator

# Предсказуемые имена индексов и ограничений: иначе их нельзя будет изменить миграцией.
NAMING_CONVENTION = {
    "ix": "ix_%(column_0_label)s",
    "uq": "uq_%(table_name)s_%(column_0_name)s",
    "ck": "ck_%(table_name)s_%(constraint_name)s",
    "fk": "fk_%(table_name)s_%(column_0_name)s_%(referred_table_name)s",
    "pk": "pk_%(table_name)s",
}


class Base(DeclarativeBase):
    """База таблиц всех контекстов."""

    metadata = MetaData(naming_convention=NAMING_CONVENTION)


class UtcDateTime(TypeDecorator[datetime]):
    """Момент времени в UTC.

    SQLite не хранит часовой пояс: записываем UTC без пояса, читаем обратно с UTC.
    Время без пояса записать нельзя — в этом проекте оно всегда ошибка.
    """

    impl = DateTime
    cache_ok = True

    def process_bind_param(self, value: datetime | None, dialect: Dialect) -> datetime | None:  # noqa: ARG002 — сигнатура SQLAlchemy
        if value is None:
            return None
        if value.tzinfo is None:
            raise ValueError(f"время без часового пояса нельзя сохранить: {value}")
        return value.astimezone(UTC).replace(tzinfo=None)

    def process_result_value(self, value: datetime | None, dialect: Dialect) -> datetime | None:  # noqa: ARG002 — сигнатура SQLAlchemy
        return None if value is None else value.replace(tzinfo=UTC)
