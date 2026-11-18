"""Таблицы контекста «Оповещения». Пациент — внешний ключ на таблицу контекста «Пациенты»."""

from datetime import date, datetime
from uuid import UUID

from sqlalchemy import Date, ForeignKey, Index, SmallInteger, String, UniqueConstraint, Uuid
from sqlalchemy.orm import Mapped, mapped_column

from kardiomonitor.platform.db.base import Base, UtcDateTime


class PatientAlertRow(Base):
    """Оповещение пациента. Значения вида — в своих колонках, у других видов — NULL.

    Уникальность — защита от дубликатов при повторе, перезапуске и втором процессе (ADR-0009):
    у дневных `episode_started_at` — NULL, у «высокого пульса» `day` — NULL, а NULL не равен
    NULL, поэтому каждое ограничение действует только на свой вид.
    """

    __tablename__ = "patient_alerts"
    __table_args__ = (
        UniqueConstraint("patient_id", "kind", "day", name="uq_patient_alerts_daily"),
        UniqueConstraint(
            "patient_id", "kind", "episode_started_at", name="uq_patient_alerts_episode"
        ),
        Index("ix_patient_alerts_patient_created", "patient_id", "created_at"),
        Index("ix_patient_alerts_patient_feed", "patient_id", "feed_position"),
    )

    id: Mapped[UUID] = mapped_column(Uuid, primary_key=True)
    patient_id: Mapped[UUID] = mapped_column(Uuid, ForeignKey("patients.id", ondelete="CASCADE"))
    kind: Mapped[str] = mapped_column(String(32))
    day: Mapped[date | None] = mapped_column(Date)
    episode_started_at: Mapped[datetime | None] = mapped_column(UtcDateTime)
    episode_last_at: Mapped[datetime | None] = mapped_column(UtcDateTime)
    max_heart_rate: Mapped[int | None] = mapped_column(SmallInteger)
    threshold: Mapped[int | None]
    """Порог: уд/мин, шагов в день или тренировочных минут — по виду оповещения."""
    training_minutes: Mapped[int | None]
    target_training_minutes: Mapped[int | None]
    steps: Mapped[int | None]
    target_steps: Mapped[int | None]
    created_at: Mapped[datetime] = mapped_column(UtcDateTime)
    viewed_at: Mapped[datetime | None] = mapped_column(UtcDateTime)
    feed_position: Mapped[int] = mapped_column(unique=True)
    """Место в ленте телефона: растёт с каждой записью оповещения (курсор `after`)."""
