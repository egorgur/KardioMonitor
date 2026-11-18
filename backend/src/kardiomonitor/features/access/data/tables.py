"""Таблицы контекста «Доступ»."""

from datetime import datetime
from uuid import UUID

from sqlalchemy import ForeignKey, SmallInteger, String, Uuid
from sqlalchemy.orm import Mapped, mapped_column

from kardiomonitor.platform.db.base import Base, UtcDateTime


class ClinicianRow(Base):
    """Врач. Пароль — только хеш Argon2id."""

    __tablename__ = "clinicians"

    id: Mapped[UUID] = mapped_column(Uuid, primary_key=True)
    login: Mapped[str] = mapped_column(String(32), unique=True)
    name: Mapped[str] = mapped_column(String(100))
    password_hash: Mapped[str] = mapped_column(String(255))
    failed_sign_ins: Mapped[int] = mapped_column(SmallInteger, default=0)
    locked_until: Mapped[datetime | None] = mapped_column(UtcDateTime)


class ClinicianSessionRow(Base):
    """Сеанс врача: отпечаток токена из cookie (сам токен не хранится)."""

    __tablename__ = "clinician_sessions"

    token_fingerprint: Mapped[str] = mapped_column(String(64), primary_key=True)
    clinician_id: Mapped[UUID] = mapped_column(
        Uuid, ForeignKey("clinicians.id", ondelete="CASCADE"), index=True
    )
    started_at: Mapped[datetime] = mapped_column(UtcDateTime)
    last_seen_at: Mapped[datetime] = mapped_column(UtcDateTime)


class AccessRecordRow(Base):
    """Запись журнала доступа: врач, действие, объект, момент. Без ФИО и диагноза (ТР-11, п. 6)."""

    __tablename__ = "access_records"

    id: Mapped[int] = mapped_column(primary_key=True, autoincrement=True)
    clinician_id: Mapped[UUID] = mapped_column(Uuid, ForeignKey("clinicians.id"), index=True)
    action: Mapped[str] = mapped_column(String(32))
    subject_id: Mapped[UUID] = mapped_column(Uuid, index=True)
    at: Mapped[datetime] = mapped_column(UtcDateTime)
