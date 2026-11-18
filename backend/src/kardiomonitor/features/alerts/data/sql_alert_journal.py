"""Журнал оповещений в базе."""

from collections.abc import Collection
from datetime import date, datetime

from sqlalchemy import func, select, update
from sqlalchemy.ext.asyncio import AsyncSession

from kardiomonitor.features.alerts.data.tables import PatientAlertRow
from kardiomonitor.features.alerts.domain.alert_journal import FeedEntry
from kardiomonitor.features.alerts.domain.high_heart_rate_episode import HighHeartRateEpisode
from kardiomonitor.features.alerts.domain.patient_alert import (
    AlertDetails,
    AlertId,
    AlertKind,
    DailyTargetResult,
    LowActivityResult,
    PatientAlert,
    TrainingMinutesExceeded,
)
from kardiomonitor.features.monitoring.domain.heart_rate import HeartRate
from kardiomonitor.features.patients.domain.patient import PatientId


class SqlAlertJournal:
    """Реализация `AlertJournal`. Транзакцию фиксирует сценарий (ADR-0005)."""

    def __init__(self, session: AsyncSession) -> None:
        self._session = session

    async def save(self, alert: PatientAlert) -> None:
        # Позиция — следующая в ленте: SQLite пишет по одному, второй процесс получит отказ
        # уникальности и повторит работу при следующем приёме или запуске (ADR-0009).
        position = await self._next_feed_position()
        row = await self._session.get(PatientAlertRow, alert.id)
        if row is not None and alert.kind is AlertKind.TRAINING_MINUTES_EXCEEDED:
            return
        if row is None:
            row = PatientAlertRow(id=alert.id, patient_id=alert.patient_id)
            self._session.add(row)
        _fill(row, alert)
        row.feed_position = position
        await self._session.flush()

    async def high_heart_rate_between(
        self, patient_id: PatientId, start: datetime, end: datetime
    ) -> list[PatientAlert]:
        rows = await self._session.scalars(
            select(PatientAlertRow).where(
                PatientAlertRow.patient_id == patient_id,
                PatientAlertRow.kind == AlertKind.HIGH_HEART_RATE,
                PatientAlertRow.episode_started_at <= end,
                PatientAlertRow.episode_last_at >= start,
            )
        )
        return [_to_alert(row) for row in rows]

    async def has_daily_alerts(self, patient_id: PatientId, day: date) -> bool:
        found = await self._session.scalar(
            select(PatientAlertRow.id)
            .where(
                PatientAlertRow.patient_id == patient_id,
                PatientAlertRow.day == day,
                PatientAlertRow.kind.in_([kind for kind in AlertKind if kind.is_scheduled]),
            )
            .limit(1)
        )
        return found is not None

    async def has_kind_on(self, patient_id: PatientId, kind: AlertKind, day: date) -> bool:
        found = await self._session.scalar(
            select(PatientAlertRow.id)
            .where(
                PatientAlertRow.patient_id == patient_id,
                PatientAlertRow.kind == kind,
                PatientAlertRow.day == day,
            )
            .limit(1)
        )
        return found is not None

    async def latest(
        self, patient_ids: Collection[PatientId], *, kind: AlertKind | None, limit: int
    ) -> list[PatientAlert]:
        query = select(PatientAlertRow).where(PatientAlertRow.patient_id.in_(patient_ids))
        if kind is not None:
            query = query.where(PatientAlertRow.kind == kind)
        rows = await self._session.scalars(
            query.order_by(
                PatientAlertRow.created_at.desc(), PatientAlertRow.feed_position.desc()
            ).limit(limit)
        )
        return [_to_alert(row) for row in rows]

    async def mark_viewed(self, patient_id: PatientId, alert_id: AlertId, at: datetime) -> bool:
        row = await self._session.get(PatientAlertRow, alert_id)
        if row is None or row.patient_id != patient_id:
            return False
        await self._session.execute(
            update(PatientAlertRow)
            .where(PatientAlertRow.id == alert_id, PatientAlertRow.viewed_at.is_(None))
            .values(viewed_at=at)
        )
        return True

    async def unviewed_counts(self, patient_ids: Collection[PatientId]) -> dict[PatientId, int]:
        rows = await self._session.execute(
            select(PatientAlertRow.patient_id, func.count())
            .where(PatientAlertRow.patient_id.in_(patient_ids), PatientAlertRow.viewed_at.is_(None))
            .group_by(PatientAlertRow.patient_id)
        )
        counts = {PatientId(patient_id): count for patient_id, count in rows}
        return {patient_id: counts.get(patient_id, 0) for patient_id in patient_ids}

    async def feed(
        self,
        patient_id: PatientId,
        *,
        after: int,
        limit: int,
        kinds: Collection[AlertKind] | None = None,
    ) -> list[FeedEntry]:
        query = select(PatientAlertRow).where(
            PatientAlertRow.patient_id == patient_id, PatientAlertRow.feed_position > after
        )
        if kinds is not None:
            query = query.where(PatientAlertRow.kind.in_(kinds))
        rows = await self._session.scalars(
            query.order_by(PatientAlertRow.feed_position).limit(limit)
        )
        return [FeedEntry(row.feed_position, _to_alert(row)) for row in rows]

    async def _next_feed_position(self) -> int:
        last = await self._session.scalar(select(func.max(PatientAlertRow.feed_position)))
        return (last or 0) + 1


def _fill(row: PatientAlertRow, alert: PatientAlert) -> None:
    row.kind = alert.kind
    row.day = alert.day
    row.created_at = alert.created_at
    row.viewed_at = alert.viewed_at
    details = alert.details
    match details:
        case HighHeartRateEpisode():
            row.episode_started_at = details.started_at
            row.episode_last_at = details.last_at
            row.max_heart_rate = details.max_heart_rate.bpm
            row.threshold = details.threshold_bpm
        case DailyTargetResult():
            row.training_minutes = details.training_minutes
            row.target_training_minutes = details.target_training_minutes
            row.steps = details.steps
            row.target_steps = details.target_steps
        case TrainingMinutesExceeded():
            row.training_minutes = details.training_minutes
            row.threshold = details.threshold_minutes
        case LowActivityResult():
            row.steps = details.steps
            row.threshold = details.threshold_steps


def _to_alert(row: PatientAlertRow) -> PatientAlert:
    kind = AlertKind(row.kind)
    return PatientAlert(
        id=AlertId(row.id),
        patient_id=PatientId(row.patient_id),
        kind=kind,
        day=row.day,
        details=_details(kind, row),
        created_at=row.created_at,
        viewed_at=row.viewed_at,
    )


def _details(kind: AlertKind, row: PatientAlertRow) -> AlertDetails:
    match kind:
        case AlertKind.HIGH_HEART_RATE:
            return HighHeartRateEpisode(
                started_at=_required(row.episode_started_at),
                last_at=_required(row.episode_last_at),
                max_heart_rate=HeartRate(_required(row.max_heart_rate)),
                threshold_bpm=_required(row.threshold),
            )
        case AlertKind.TRAINING_MINUTES_EXCEEDED:
            return TrainingMinutesExceeded(
                training_minutes=_required(row.training_minutes),
                threshold_minutes=_required(row.threshold),
            )
        case AlertKind.LOW_ACTIVITY:
            return LowActivityResult(
                steps=_required(row.steps), threshold_steps=_required(row.threshold)
            )
        case AlertKind.DAILY_TARGET_MET | AlertKind.DAILY_TARGET_MISSED:
            return DailyTargetResult(
                training_minutes=_required(row.training_minutes),
                target_training_minutes=_required(row.target_training_minutes),
                steps=_required(row.steps),
                target_steps=_required(row.target_steps),
            )


def _required[T](value: T | None) -> T:
    """Колонки своего вида `_fill` заполняет всегда; NULL — запись испорчена."""
    if value is None:
        raise ValueError("в записи оповещения нет значения его вида")
    return value
