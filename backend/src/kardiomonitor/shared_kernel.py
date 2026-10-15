"""Общее ядро: минимум типов, которые разрешено импортировать домену и сценариям любого
контекста. Расширять — только через ADR (architecture.md)."""

from datetime import datetime
from typing import Protocol


class DomainRuleViolation(Exception):
    """Нарушено правило предметной области. Сообщение — по-русски, `code` — для клиента API."""

    def __init__(self, message: str, *, code: str) -> None:
        super().__init__(message)
        self.code = code


class Clock(Protocol):
    """Источник текущего времени: в тестах подменяется фиксированным."""

    def now(self) -> datetime: ...


class Transaction(Protocol):
    """Транзакция запроса: изменения сохраняются только после `commit` (ADR-0005).

    Незафиксированное откатывается, когда запрос завершается.
    """

    async def commit(self) -> None: ...
