"""Таблицы контекста «Пациенты»."""

from datetime import datetime
from uuid import UUID

from sqlalchemy import String, Uuid
from sqlalchemy.orm import Mapped, mapped_column

from kardiomonitor.platform.db.base import Base, UtcDateTime


class PatientRow(Base):
    """Пациент и отпечаток токена его телефона (сам токен не хранится)."""

    __tablename__ = "patients"

    id: Mapped[UUID] = mapped_column(Uuid, primary_key=True)
    name: Mapped[str] = mapped_column(String(100))
    time_zone: Mapped[str] = mapped_column(String(64))
    registered_at: Mapped[datetime] = mapped_column(UtcDateTime)
    token_fingerprint: Mapped[str] = mapped_column(String(64), unique=True)
