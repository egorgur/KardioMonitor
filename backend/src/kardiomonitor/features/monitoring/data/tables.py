"""Таблицы контекста «Мониторинг». Пациент — внешний ключ на таблицу контекста «Пациенты»."""

from datetime import datetime
from uuid import UUID

from sqlalchemy import ForeignKey, Index, SmallInteger, String, Uuid, text
from sqlalchemy.orm import Mapped, mapped_column

from kardiomonitor.platform.db.base import Base, UtcDateTime


class ActivityMinuteRow(Base):
    """Минута активности пациента. Первичный ключ (пациент, минута) — одна запись на минуту."""

    __tablename__ = "activity_minutes"

    patient_id: Mapped[UUID] = mapped_column(
        Uuid, ForeignKey("patients.id", ondelete="CASCADE"), primary_key=True
    )
    started_at: Mapped[datetime] = mapped_column(UtcDateTime, primary_key=True)
    steps: Mapped[int]
    heart_rate: Mapped[int | None] = mapped_column(SmallInteger)
    kind: Mapped[str] = mapped_column(String(16))
    kind_code: Mapped[int | None] = mapped_column(SmallInteger)
    intensity: Mapped[int] = mapped_column(SmallInteger)


class LiveHeartRateRow(Base):
    """Значение «пульса сейчас»."""

    __tablename__ = "live_heart_rate_readings"

    patient_id: Mapped[UUID] = mapped_column(
        Uuid, ForeignKey("patients.id", ondelete="CASCADE"), primary_key=True
    )
    measured_at: Mapped[datetime] = mapped_column(UtcDateTime, primary_key=True)
    heart_rate: Mapped[int] = mapped_column(SmallInteger)


class LiveHeartRateRequestRow(Base):
    """Запрос врача на непрерывное измерение пульса."""

    __tablename__ = "live_heart_rate_requests"
    __table_args__ = (
        Index(
            "uq_live_heart_rate_requests_active_patient",
            "patient_id",
            unique=True,
            sqlite_where=text("status IN ('waiting_for_phone', 'running')"),
        ),
    )

    id: Mapped[UUID] = mapped_column(Uuid, primary_key=True)
    patient_id: Mapped[UUID] = mapped_column(
        Uuid, ForeignKey("patients.id", ondelete="CASCADE"), index=True
    )
    duration_minutes: Mapped[int] = mapped_column(SmallInteger)
    requested_at: Mapped[datetime] = mapped_column(UtcDateTime, index=True)
    expires_at: Mapped[datetime] = mapped_column(UtcDateTime)
    status: Mapped[str] = mapped_column(String(32))
    started_at: Mapped[datetime | None] = mapped_column(UtcDateTime)
    finished_at: Mapped[datetime | None] = mapped_column(UtcDateTime)
    failure_reason: Mapped[str | None] = mapped_column(String(48))
