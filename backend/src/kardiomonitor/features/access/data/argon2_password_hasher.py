"""Хеширование паролей Argon2id (`argon2-cffi`, ADR-0011)."""

import asyncio

from argon2 import PasswordHasher as Argon2
from argon2.exceptions import InvalidHashError, VerificationError

from kardiomonitor.features.access.domain.password import Password


class Argon2PasswordHasher:
    """Реализация `PasswordHasher`. Параметры — по умолчанию библиотеки (RFC 9106, low memory).

    Хеширование идёт в отдельном потоке: десятки миллисекунд ЦП не блокируют цикл событий.
    """

    def __init__(self) -> None:
        self._argon2 = Argon2()
        self._absent_hash = self._argon2.hash("пароль несуществующего врача")

    async def hash(self, password: Password) -> str:
        return await asyncio.to_thread(self._argon2.hash, password.value)

    async def verify(self, password_hash: str | None, candidate: str) -> bool:
        return await asyncio.to_thread(self._verify, password_hash, candidate)

    def _verify(self, password_hash: str | None, candidate: str) -> bool:
        try:
            matches = self._argon2.verify(password_hash or self._absent_hash, candidate)
        except (VerificationError, InvalidHashError):
            return False
        return matches and password_hash is not None
