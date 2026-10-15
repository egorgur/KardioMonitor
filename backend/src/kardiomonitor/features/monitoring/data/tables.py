"""Таблицы контекста «Мониторинг». Пациент — внешний ключ на таблицу контекста «Пациенты»."""

from datetime import datetime
from uuid import UUID

from sqlalchemy import ForeignKey, SmallInteger, String, Uuid
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
