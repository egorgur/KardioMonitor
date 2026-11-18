"""Пароль врача и порт его хеширования (ADR-0011)."""

from dataclasses import dataclass, field
from typing import Protocol

from kardiomonitor.shared_kernel import DomainRuleViolation

MIN_PASSWORD_LENGTH = 12
MAX_PASSWORD_LENGTH = 128


@dataclass(frozen=True, slots=True)
class Password:
    """Новый пароль врача: 12–128 знаков (ТР-11, п. 5). `repr` значение не раскрывает."""

    value: str = field(repr=False)

    def __post_init__(self) -> None:
        if not MIN_PASSWORD_LENGTH <= len(self.value) <= MAX_PASSWORD_LENGTH:
            raise DomainRuleViolation(
                f"пароль — от {MIN_PASSWORD_LENGTH} до {MAX_PASSWORD_LENGTH} знаков",
                code="weak_password",
            )


class PasswordHasher(Protocol):
    """Хеширование паролей (Argon2id). Медленное намеренно — вызывается не в цикле событий."""

    async def hash(self, password: Password) -> str: ...

    async def verify(self, password_hash: str | None, candidate: str) -> bool:
        """Подходит ли пароль к хешу. `None` — врача нет: проверка всё равно занимает то же
        время, чтобы по времени ответа нельзя было узнать, есть ли логин."""
        ...
