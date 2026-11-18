"""Таблицы контекста «Пациенты»."""

from datetime import date, datetime
from uuid import UUID

from sqlalchemy import ForeignKey, SmallInteger, String, Uuid
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


class AttendingClinicianRow(Base):
    """Лечащий врач пациента (ТР-11). Отдельная таблица: у пациентов демо врача нет, а таблицу
    `patients` без миграций (ADR-0007) расширить нельзя."""

    __tablename__ = "attending_clinicians"

    patient_id: Mapped[UUID] = mapped_column(
        Uuid, ForeignKey("patients.id", ondelete="CASCADE"), primary_key=True
    )
    clinician_id: Mapped[UUID] = mapped_column(Uuid, ForeignKey("clinicians.id"), index=True)


class PatientProfileRow(Base):
    """Карточка пациента от клиники (ТР-07): особая категория персональных данных."""

    __tablename__ = "patient_profiles"

    patient_id: Mapped[UUID] = mapped_column(
        Uuid, ForeignKey("patients.id", ondelete="CASCADE"), primary_key=True
    )
    last_name: Mapped[str] = mapped_column(String(32))
    first_name: Mapped[str] = mapped_column(String(32))
    middle_name: Mapped[str | None] = mapped_column(String(32))
    birth_date: Mapped[date]
    height_cm: Mapped[int] = mapped_column(SmallInteger)
    weight_kg: Mapped[float]
    diagnosis: Mapped[str] = mapped_column(String(1000))


class EnrollmentCodeRow(Base):
    """Код привязки телефона: только отпечаток; погашенный хранит момент погашения."""

    __tablename__ = "enrollment_codes"

    fingerprint: Mapped[str] = mapped_column(String(64), primary_key=True)
    patient_id: Mapped[UUID] = mapped_column(
        Uuid, ForeignKey("patients.id", ondelete="CASCADE"), index=True
    )
    expires_at: Mapped[datetime] = mapped_column(UtcDateTime)
    redeemed_at: Mapped[datetime | None] = mapped_column(UtcDateTime)
