"""Таблицы контекста «Реабилитация». Пациент — внешний ключ на таблицу контекста «Пациенты»."""

from datetime import date, time
from typing import Any
from uuid import UUID

from sqlalchemy import JSON, Date, ForeignKey, SmallInteger, Time, Uuid
from sqlalchemy.orm import Mapped, mapped_column

from kardiomonitor.platform.db.base import Base


class RehabPlanRevisionRow(Base):
    """Версия плана реабилитации (ADR-0010). Этапы — JSON: живут только вместе с версией."""

    __tablename__ = "rehab_plan_revisions"

    patient_id: Mapped[UUID] = mapped_column(
        Uuid, ForeignKey("patients.id", ondelete="CASCADE"), primary_key=True
    )
    effective_from: Mapped[date] = mapped_column(Date, primary_key=True)
    program_start_date: Mapped[date] = mapped_column(Date)
    duration_days: Mapped[int] = mapped_column(SmallInteger)
    stages: Mapped[list[dict[str, Any]]] = mapped_column(JSON)
    walking_steps_threshold: Mapped[int] = mapped_column(SmallInteger)
    high_heart_rate_threshold: Mapped[int] = mapped_column(SmallInteger)
    low_activity_steps: Mapped[int]
    daily_alert_time_utc: Mapped[time] = mapped_column(Time)
    training_minutes_upper_threshold: Mapped[int | None] = mapped_column(SmallInteger)


class RehabDayOutcomeRow(Base):
    """Итог дня пациента — кеш (domain/day_outcomes.py): нормы дня и факт по минутам дня."""

    __tablename__ = "rehab_day_outcomes"

    patient_id: Mapped[UUID] = mapped_column(
        Uuid, ForeignKey("patients.id", ondelete="CASCADE"), primary_key=True
    )
    day: Mapped[date] = mapped_column(Date, primary_key=True)
    zone_lower_bpm: Mapped[int] = mapped_column(SmallInteger)
    zone_upper_bpm: Mapped[int] = mapped_column(SmallInteger)
    target_training_minutes: Mapped[int] = mapped_column(SmallInteger)
    target_steps: Mapped[int]
    training_minutes: Mapped[int] = mapped_column(SmallInteger)
    steps: Mapped[int]
    walking_minutes: Mapped[int] = mapped_column(SmallInteger)
    walking_minutes_without_heart_rate: Mapped[int] = mapped_column(SmallInteger)
    has_data: Mapped[bool]
