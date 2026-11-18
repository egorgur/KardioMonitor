"""Слайс «Пересчёт итогов дня»: подписчик обновляет итоги дней, за которые пришли минуты."""

from datetime import date, datetime

from kardiomonitor.features.monitoring.domain.minute_batch import MinuteBatch
from kardiomonitor.features.monitoring.domain.monitoring_events import MinutesReceived
from kardiomonitor.features.rehab.day_outcome_refresh import DayOutcomeRefresh
from kardiomonitor.features.rehab.domain.daily_target_progress import DailyTargetProgress
from tests.fakes import FixedClock
from tests.features.monitoring.fakes import FakeActivityHistory
from tests.features.monitoring.minutes import minute
from tests.features.rehab.fakes import FakeDayOutcomes, FakeRehabPlans
from tests.features.rehab.plans import MOSCOW, PATIENT, TODAY_NOON


async def _receive(
    refresh: DayOutcomeRefresh, history: FakeActivityHistory, day: int, minutes: int
) -> None:
    start = datetime(2026, 10, day, 23, 50, tzinfo=MOSCOW)
    batch = MinuteBatch(
        tuple(minute(i, steps=400, heart_rate=100, start=start) for i in range(minutes))
    )
    await history.save(PATIENT.id, batch)
    await refresh.minutes_received(MinutesReceived(PATIENT, batch.minutes))


def _subject() -> tuple[DayOutcomeRefresh, FakeActivityHistory, FakeDayOutcomes]:
    history, outcomes = FakeActivityHistory(), FakeDayOutcomes()
    refresh = DayOutcomeRefresh(FakeRehabPlans(outcomes), history, outcomes, FixedClock(TODAY_NOON))
    return refresh, history, outcomes


def _training_minutes(outcomes: FakeDayOutcomes, day: int) -> int:
    progress: DailyTargetProgress = outcomes.outcomes[PATIENT.id, date(2026, 10, day)]
    return progress.training_minutes


async def test_поздние_минуты_пересчитывают_итог_прошлого_дня() -> None:
    refresh, history, outcomes = _subject()
    await _receive(refresh, history, 5, 5)

    await _receive(refresh, history, 5, 8)

    assert _training_minutes(outcomes, 5) == 8, "итог — по всем минутам дня, а не по пакету"


async def test_минуты_через_полночь_обновляют_оба_дня_пациента() -> None:
    refresh, history, outcomes = _subject()

    await _receive(refresh, history, 5, 15)

    assert (_training_minutes(outcomes, 5), _training_minutes(outcomes, 6)) == (10, 5)
    assert not outcomes.outcomes[PATIENT.id, date(2026, 10, 5)].is_met
