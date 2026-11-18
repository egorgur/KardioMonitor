"""Проверка сохранённого полного дня, включая повторы, исправления и поздние пакеты."""

from dataclasses import replace
from datetime import date, datetime, timedelta

from tests.features.alerts.ports import Ports
from tests.features.monitoring.minutes import minute
from tests.features.rehab.plans import MOSCOW, PATIENT, TODAY_NOON

from kardiomonitor.features.alerts.daily_check.check_daily_alerts import check_daily_alerts
from kardiomonitor.features.alerts.domain.patient_alert import AlertKind, TrainingMinutesExceeded
from kardiomonitor.features.alerts.training_minutes import TrainingMinutesWatch
from kardiomonitor.features.monitoring.domain.minute_batch import MinuteBatch
from kardiomonitor.features.monitoring.domain.monitoring_events import MinutesReceived
from kardiomonitor.features.rehab.domain.rehab_plan import RehabPlan

DAY = date(2026, 10, 12)
START = datetime(2026, 10, 12, 8, tzinfo=MOSCOW)


def _watch(ports: Ports, threshold: int | None = 60) -> TrainingMinutesWatch:
    plan = RehabPlan.default_for(PATIENT)
    settings = replace(plan.current.settings, training_minutes_upper_threshold=threshold)
    ports.plans.plans[PATIENT.id] = plan.revised(
        plan.current.program,
        settings,
        today=date(2026, 10, 1),
    )
    return TrainingMinutesWatch(ports.plans, ports.history, ports.journal, ports.clock)


async def test_пакеты_суммируются_повтор_рост_и_уменьшение_не_меняют_снимок(ports: Ports) -> None:
    watch = _watch(ports)
    first = tuple(minute(i, steps=80, heart_rate=100, start=START) for i in range(60))
    await ports.history.save(PATIENT.id, MinuteBatch(first))
    await watch.minutes_received(MinutesReceived(PATIENT, first))
    assert ports.journal.alerts == {}
    extra = (minute(60, steps=80, heart_rate=100, start=START),)
    await ports.history.save(PATIENT.id, MinuteBatch(extra))
    await watch.minutes_received(MinutesReceived(PATIENT, extra))
    alert = ports.journal.of_kind(AlertKind.TRAINING_MINUTES_EXCEEDED)[0]
    await ports.journal.mark_viewed(PATIENT.id, alert.id, TODAY_NOON)
    before = await ports.journal.feed(PATIENT.id, after=0, limit=10)
    for minutes in (
        extra,
        (minute(61, steps=80, heart_rate=100, start=START),),
        (minute(0, steps=0, start=START),),
    ):
        await ports.history.save(PATIENT.id, MinuteBatch(minutes))
        await watch.minutes_received(MinutesReceived(PATIENT, minutes))
    assert await ports.journal.feed(PATIENT.id, after=0, limit=10) == before
    assert before[0].alert.details == TrainingMinutesExceeded(61, 60)
    assert before[0].alert.is_viewed


async def test_исправление_без_новых_минут_впервые_вызывает_превышение(ports: Ports) -> None:
    watch = _watch(ports, threshold=1)
    initial = (minute(0, steps=80, heart_rate=100, start=START), minute(1, start=START))
    await ports.history.save(PATIENT.id, MinuteBatch(initial))
    await watch.minutes_received(MinutesReceived(PATIENT, initial))
    assert not ports.journal.alerts
    corrected = (minute(1, steps=80, heart_rate=100, start=START),)
    receipt = await ports.history.save(PATIENT.id, MinuteBatch(corrected))
    await watch.minutes_received(MinutesReceived(PATIENT, corrected))
    assert receipt.new_minutes == 0
    assert ports.journal.of_kind(AlertKind.TRAINING_MINUTES_EXCEEDED)[0].details == (
        TrainingMinutesExceeded(2, 1)
    )


async def test_пакет_через_полночь_проверяет_два_дня_в_поясе_пациента(ports: Ports) -> None:
    watch = _watch(ports, threshold=1)
    start = datetime(2026, 10, 10, 23, 58, tzinfo=MOSCOW)
    minutes = tuple(minute(i, steps=80, heart_rate=100, start=start) for i in range(4))
    await ports.history.save(PATIENT.id, MinuteBatch(minutes))
    await watch.minutes_received(MinutesReceived(PATIENT, minutes))
    assert {a.day for a in ports.journal.alerts.values()} == {
        date(2026, 10, 10),
        date(2026, 10, 11),
    }
    assert all(a.details == TrainingMinutesExceeded(2, 1) for a in ports.journal.alerts.values())


async def test_утреннее_превышение_не_блокирует_плановые_оповещения_вечером(ports: Ports) -> None:
    watch = _watch(ports, threshold=1)
    minutes = tuple(minute(i, steps=80, heart_rate=100, start=START) for i in range(2))
    await ports.history.save(PATIENT.id, MinuteBatch(minutes))
    await watch.minutes_received(MinutesReceived(PATIENT, minutes))
    assert not await ports.journal.has_daily_alerts(PATIENT.id, DAY)
    ports.clock.current = TODAY_NOON + timedelta(hours=8)
    created = await check_daily_alerts(
        registry=ports.registry,
        plans=ports.plans,
        history=ports.history,
        journal=ports.journal,
        clock=ports.clock,
        transaction=ports.transaction,
    )
    assert created >= 2
    assert {a.kind for a in ports.journal.alerts.values() if a.patient_id == PATIENT.id} == {
        AlertKind.TRAINING_MINUTES_EXCEEDED,
        AlertKind.DAILY_TARGET_MISSED,
        AlertKind.LOW_ACTIVITY,
    }


async def test_превышение_не_отменяет_выполнение_нижней_нормы(ports: Ports) -> None:
    watch = _watch(ports)
    minutes = tuple(minute(i, steps=200, heart_rate=100, start=START) for i in range(61))
    await ports.history.save(PATIENT.id, MinuteBatch(minutes))
    await watch.minutes_received(MinutesReceived(PATIENT, minutes))
    ports.clock.current = TODAY_NOON + timedelta(hours=8)
    await check_daily_alerts(
        registry=ports.registry,
        plans=ports.plans,
        history=ports.history,
        journal=ports.journal,
        clock=ports.clock,
        transaction=ports.transaction,
    )
    assert {a.kind for a in ports.journal.alerts.values() if a.patient_id == PATIENT.id} == {
        AlertKind.TRAINING_MINUTES_EXCEEDED,
        AlertKind.DAILY_TARGET_MET,
    }


async def test_правка_проверяет_сегодня_пациента_а_не_сегодня_utc(ports: Ports) -> None:
    watch = _watch(ports, threshold=1)
    start = datetime(2026, 10, 13, 0, 0, tzinfo=MOSCOW)
    minutes = tuple(minute(i, steps=80, heart_rate=100, start=start) for i in range(2))
    await ports.history.save(PATIENT.id, MinuteBatch(minutes))
    now = start + timedelta(minutes=5)
    await watch.plan_changed(PATIENT, ports.plans.plans[PATIENT.id], now=now)
    assert [a.day for a in ports.journal.alerts.values()] == [date(2026, 10, 13)]
