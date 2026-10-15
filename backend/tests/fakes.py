"""Общие фейки тестов: часы, транзакция, подмена реализаций в dishka (testing.md)."""

from collections.abc import Callable
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path

from dishka import Provider, Scope

from kardiomonitor.app.settings import Settings

NOW = datetime(2026, 10, 16, 9, 30, tzinfo=UTC)


@dataclass
class FixedClock:
    """Часы, которые всегда показывают одно и то же время."""

    current: datetime = NOW

    def now(self) -> datetime:
        return self.current


@dataclass
class FakeTransaction:
    """Считает фиксации: сценарий обязан зафиксировать изменения до ответа (ADR-0005)."""

    commits: int = 0

    async def commit(self) -> None:
        self.commits += 1


def replacing(*pairs: tuple[object, object]) -> Provider:
    """Провайдер dishka, который подменяет реализации портов готовыми объектами."""
    provider = Provider(scope=Scope.APP)
    for port, instance in pairs:
        provider.provide(_constant(instance), provides=port, override=True)
    return provider


def _constant(instance: object) -> Callable[[], object]:
    def factory() -> object:
        return instance

    return factory


def settings_for_tests(database_url: str = "sqlite+aiosqlite:///:memory:") -> Settings:
    """Настройки без собранной панели и без ключа панели."""
    return Settings(database_url=database_url, frontend_dist=Path("/nonexistent"))
