"""Токен загрузки — секрет телефона пациента."""

import hashlib
import secrets
from dataclasses import dataclass, field
from typing import Self


@dataclass(frozen=True, slots=True)
class UploadToken:
    """Секрет для `Authorization: Bearer`. Телефон видит его один раз, база — только отпечаток.

    `repr` не раскрывает значение: токен не попадает в журнал и трассировку.
    """

    value: str = field(repr=False)

    @classmethod
    def issue(cls) -> Self:
        """Новый случайный токен (256 бит)."""
        return cls(secrets.token_urlsafe(32))

    @property
    def fingerprint(self) -> str:
        """SHA-256 токена: по нему ищем пациента, не храня сам токен."""
        return hashlib.sha256(self.value.encode()).hexdigest()
