"""Журнал оповещений на настоящей SQLite: значения без потерь, лента, без дубликатов."""

from collections.abc import AsyncIterator
from dataclasses import replace
from datetime import UTC, date, datetime, timedelta
from pathlib import Path

import pytest
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from kardiomonitor.features.alerts.data.sql_alert_journal import SqlAlertJournal
from kardiomonitor.features.alerts.domain.high_heart_rate_episode import HighHeartRateEpisode
from kardiomonitor.features.alerts.domain.patient_alert import (
    AlertKind,
    DailyTargetResult,
    LowActivityResult,
    PatientAlert,
    TrainingMinutesExceeded,
)
from kardiomonitor.features.monitoring.domain.heart_rate import HeartRate
from kardiomonitor.features.patients.data.sql_patient_registry import SqlPatientRegistry
from kardiomonitor.features.patients.domain.upload_token import UploadToken
from tests.database import database_session
from tests.features.alerts.ports import OTHER
from tests.features.rehab.plans import PATIENT

NOW = datetime(2026, 10, 12, 17, 0, tzinfo=UTC)
DAY = date(2026, 10, 12)


@pytest.fixture
async def journal(tmp_path: Path) -> AsyncIterator[SqlAlertJournal]:
    async with database_session(tmp_path) as session:
        await _add_patients(session)
        yield SqlAlertJournal(session)


async def _add_patients(session: AsyncSession) -> None:
    registry = SqlPatientRegistry(session)
    for patient in (PATIENT, OTHER):
        await registry.add(patient, UploadToken.issue())


def _episode(minutes: int = 5, bpm: int = 140) -> HighHeartRateEpisode:
    return HighHeartRateEpisode(NOW, NOW + timedelta(minutes=minutes), HeartRate(bpm), 130)


def _missed(day: date = DAY) -> PatientAlert:
    return PatientAlert.daily(
        PATIENT.id,
        AlertKind.DAILY_TARGET_MISSED,
        day,
        DailyTargetResult(12, 30, 6200, 10000),
        now=NOW,
    )


async def test_оповещения_всех_видов_читаются_как_записаны(journal: SqlAlertJournal) -> None:
    low = PatientAlert.daily(
        PATIENT.id, AlertKind.LOW_ACTIVITY, DAY, LowActivityResult(400, 1000), now=NOW
    )
    high = PatientAlert.high_heart_rate(PATIENT.id, _episode(), now=NOW)
    exceeded = PatientAlert.daily(
        PATIENT.id,
        AlertKind.TRAINING_MINUTES_EXCEEDED,
        DAY,
        TrainingMinutesExceeded(61, 60),
        now=NOW,
    )
    for alert in (_missed(), low, high, exceeded):
        await journal.save(alert)

    stored = await journal.latest([PATIENT.id], kind=None, limit=10)

    assert {alert.kind for alert in stored} == set(AlertKind) - {AlertKind.DAILY_TARGET_MET}
    assert next(a for a in stored if a.kind is AlertKind.HIGH_HEART_RATE) == high
    assert next(a for a in stored if a.kind is AlertKind.LOW_ACTIVITY) == low
    assert next(a for a in stored if a.kind is AlertKind.TRAINING_MINUTES_EXCEEDED) == exceeded


async def test_второе_дневное_оповещение_того_же_вида_за_день_отклоняется(
    journal: SqlAlertJournal,
) -> None:
    await journal.save(_missed())

    with pytest.raises(IntegrityError):
        await journal.save(_missed())


async def test_продлённый_эпизод_записан_поверх_и_в_конце_ленты(journal: SqlAlertJournal) -> None:
    high = PatientAlert.high_heart_rate(PATIENT.id, _episode(), now=NOW)
    await journal.save(high)
    await journal.save(_missed())

    await journal.save(high.with_episode(_episode(minutes=20, bpm=155)))

    feed = await journal.feed(PATIENT.id, after=0, limit=10)
    assert [entry.alert.kind for entry in feed] == [
        AlertKind.DAILY_TARGET_MISSED,
        AlertKind.HIGH_HEART_RATE,
    ]
    assert feed[1].alert.episode == _episode(minutes=20, bpm=155)
    assert await journal.feed(PATIENT.id, after=feed[1].position, limit=10) == []


async def test_эпизоды_ищутся_по_пересечению_с_окном(journal: SqlAlertJournal) -> None:
    await journal.save(PatientAlert.high_heart_rate(PATIENT.id, _episode(), now=NOW))

    touching = await journal.high_heart_rate_between(
        PATIENT.id, NOW + timedelta(minutes=5), NOW + timedelta(hours=1)
    )
    later = await journal.high_heart_rate_between(
        PATIENT.id, NOW + timedelta(minutes=6), NOW + timedelta(hours=1)
    )

    assert len(touching) == 1
    assert later == []


async def test_просмотренные_не_входят_в_счётчик(journal: SqlAlertJournal) -> None:
    first, second = _missed(), _missed(DAY - timedelta(days=1))
    for alert in (first, second):
        await journal.save(alert)

    marked = await journal.mark_viewed(PATIENT.id, first.id, NOW)
    remarked = await journal.mark_viewed(PATIENT.id, first.id, NOW + timedelta(hours=1))
    foreign = await journal.mark_viewed(OTHER.id, second.id, NOW)

    assert (marked, remarked, foreign) == (True, True, False)
    assert await journal.unviewed_counts([PATIENT.id, OTHER.id]) == {PATIENT.id: 1, OTHER.id: 0}
    stored = await journal.latest([PATIENT.id], kind=None, limit=10)
    assert next(a for a in stored if a.id == first.id).viewed_at == NOW


async def test_дневная_проверка_дня_видна_по_любому_виду(journal: SqlAlertJournal) -> None:
    await journal.save(_missed())

    assert await journal.has_daily_alerts(PATIENT.id, DAY)
    assert not await journal.has_daily_alerts(PATIENT.id, DAY + timedelta(days=1))
    assert not await journal.has_daily_alerts(OTHER.id, DAY)


async def test_фильтр_видов_в_sql_применяется_до_лимита_и_курсора(journal: SqlAlertJournal) -> None:
    await journal.save(
        PatientAlert.daily(
            PATIENT.id,
            AlertKind.TRAINING_MINUTES_EXCEEDED,
            DAY,
            TrainingMinutesExceeded(61, 60),
            now=NOW,
        )
    )
    await journal.save(_missed())
    await journal.save(
        PatientAlert.daily(
            PATIENT.id,
            AlertKind.TRAINING_MINUTES_EXCEEDED,
            DAY + timedelta(days=1),
            TrainingMinutesExceeded(61, 60),
            now=NOW,
        )
    )
    await journal.save(_missed(DAY + timedelta(days=1)))
    kinds = [kind for kind in AlertKind if kind is not AlertKind.TRAINING_MINUTES_EXCEEDED]
    first = await journal.feed(PATIENT.id, after=0, limit=1, kinds=kinds)
    second = await journal.feed(PATIENT.id, after=first[0].position, limit=1, kinds=kinds)
    assert first[0].position == 2
    assert second[0].position == 4
    assert first[0].alert.kind == second[0].alert.kind == AlertKind.DAILY_TARGET_MISSED


async def test_превышение_не_является_плановой_проверкой_и_не_обновляется(
    journal: SqlAlertJournal,
) -> None:
    alert = PatientAlert.daily(
        PATIENT.id,
        AlertKind.TRAINING_MINUTES_EXCEEDED,
        DAY,
        TrainingMinutesExceeded(61, 60),
        now=NOW,
    )
    await journal.save(alert)
    assert not await journal.has_daily_alerts(PATIENT.id, DAY)
    assert await journal.has_kind_on(PATIENT.id, alert.kind, DAY)
    await journal.mark_viewed(PATIENT.id, alert.id, NOW)
    before = await journal.feed(PATIENT.id, after=0, limit=10)
    await journal.save(replace(alert, details=TrainingMinutesExceeded(80, 60)))
    assert await journal.feed(PATIENT.id, after=0, limit=10) == before
    assert before[0].alert.is_viewed
    assert await journal.feed(PATIENT.id, after=0, limit=10, kinds=[AlertKind.LOW_ACTIVITY]) == []
    with pytest.raises(IntegrityError):
        await journal.save(
            PatientAlert.daily(
                PATIENT.id,
                AlertKind.TRAINING_MINUTES_EXCEEDED,
                DAY,
                TrainingMinutesExceeded(80, 60),
                now=NOW,
            )
        )
