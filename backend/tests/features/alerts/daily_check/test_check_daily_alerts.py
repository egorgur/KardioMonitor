"""Слайс «Дневная проверка»: сценарий периодической задачи на фейках (ТР-08)."""

from datetime import UTC, date, datetime, timedelta

from kardiomonitor.features.alerts.daily_check.check_daily_alerts import check_daily_alerts
from kardiomonitor.features.alerts.domain.patient_alert import AlertKind, DailyTargetResult
from kardiomonitor.features.monitoring.domain.minute_batch import MinuteBatch
from tests.features.alerts.ports import OTHER, Ports
from tests.features.monitoring.minutes import minute
from tests.features.rehab.plans import MOSCOW, PATIENT

AT_1700_UTC = datetime(2026, 10, 12, 17, 0, tzinfo=UTC)


async def _check(ports: Ports, now: datetime) -> int:
    ports.clock.current = now
    return await check_daily_alerts(
        registry=ports.registry,
        plans=ports.plans,
        history=ports.history,
        journal=ports.journal,
        clock=ports.clock,
        transaction=ports.transaction,
    )


async def _walk(ports: Ports, start: datetime, minutes: int) -> None:
    walk = tuple(minute(i, steps=80, heart_rate=100, start=start) for i in range(minutes))
    await ports.history.save(PATIENT.id, MinuteBatch(walk))


async def test_в_1700_utc_норма_дня_не_выполнена_у_каждого_пациента(ports: Ports) -> None:
    await _walk(ports, datetime(2026, 10, 12, 9, tzinfo=MOSCOW), 12)

    created = await _check(ports, AT_1700_UTC)

    missed = ports.journal.of_kind(AlertKind.DAILY_TARGET_MISSED)
    assert created == 4, "у обоих пациентов — «не выполнена» и «почти не ходит»"
    assert {alert.patient_id for alert in missed} == {PATIENT.id, OTHER.id}
    own = next(alert for alert in missed if alert.patient_id == PATIENT.id)
    assert own.day == date(2026, 10, 12)
    assert own.details == DailyTargetResult(12, 30, 960, 10_000)
    assert ports.transaction.commits == 2


async def test_повторная_проверка_дня_новых_оповещений_не_создаёт(ports: Ports) -> None:
    await _check(ports, AT_1700_UTC)

    again = await _check(ports, AT_1700_UTC + timedelta(minutes=1))

    assert again == 0
    assert len(ports.journal.alerts) == 4


async def test_поздние_минуты_дня_проверку_не_повторяют(ports: Ports) -> None:
    await _check(ports, AT_1700_UTC)
    await _walk(ports, datetime(2026, 10, 12, 19, tzinfo=MOSCOW), 40)

    await _check(ports, AT_1700_UTC + timedelta(hours=1))

    assert ports.journal.of_kind(AlertKind.DAILY_TARGET_MET) == []


async def test_минуты_после_момента_проверки_не_учитываются(ports: Ports) -> None:
    await _walk(ports, datetime(2026, 10, 12, 20, 30, tzinfo=MOSCOW), 40)

    await _check(ports, AT_1700_UTC + timedelta(hours=3))

    own = [a for a in ports.journal.alerts.values() if a.patient_id == PATIENT.id]
    assert {alert.kind for alert in own} == {
        AlertKind.DAILY_TARGET_MISSED,
        AlertKind.LOW_ACTIVITY,
    }


async def test_до_времени_оповещений_ничего_не_создаётся(ports: Ports) -> None:
    created = await _check(ports, AT_1700_UTC - timedelta(minutes=1))

    assert created == 0
    assert ports.transaction.commits == 0
