"""Системные часы — реализация `shared_kernel.Clock`."""

from datetime import UTC, datetime


class SystemClock:
    """Текущее время в UTC."""

    def now(self) -> datetime:
        return datetime.now(UTC)
