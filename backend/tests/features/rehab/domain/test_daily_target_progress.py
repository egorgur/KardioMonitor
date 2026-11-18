"""Выполнение нормы за день — ТР-03, критерии приёмки (нормы 30 мин и 10 000 шагов).

Те же случаи — в Android (этап 4).
"""

from kardiomonitor.features.monitoring.domain.activity_minute import ActivityMinute
from kardiomonitor.features.rehab.domain.daily_target_progress import DailyTargetProgress
from tests.features.monitoring.minutes import minute
from tests.features.rehab.plans import targets


def _day(training: int, steps: int, *, gaps: int = 0) -> list[ActivityMinute]:
    """`training` тренировочных минут (по 80 шагов, пульс 100), остаток шагов — в одной минуте
    без ходьбы; `gaps` — минуты ходьбы без пульса."""
    minutes = [minute(i, steps=80, heart_rate=100) for i in range(training)]
    minutes += [minute(training + i, steps=80) for i in range(gaps)]
    rest = steps - 80 * (training + gaps)
    return [*minutes, minute(training + gaps, steps=rest)] if rest > 0 else minutes


def _progress(minutes: list[ActivityMinute], *, is_final: bool = True) -> DailyTargetProgress:
    return DailyTargetProgress.of(
        minutes, targets(30, 10_000), walking_steps_threshold=60, is_final=is_final
    )


def test_15_минут_и_6200_шагов_норма_не_выполнена() -> None:
    progress = _progress(_day(15, 6_200))

    assert (progress.training_minutes, progress.steps) == (15, 6_200)
    assert not progress.is_met


def test_минуты_не_обязаны_идти_подряд() -> None:
    minutes = [
        minute(i + 60 * part, steps=80, heart_rate=100) for part in range(3) for i in range(12)
    ]

    progress = _progress([*minutes, minute(500, steps=10_500 - 36 * 80)])

    assert progress.training_minutes == 36
    assert progress.is_met


def test_не_хватает_шагов_норма_не_выполнена() -> None:
    assert not _progress(_day(40, 9_000)).is_met


def test_не_хватает_минут_норма_не_выполнена() -> None:
    assert not _progress(_day(20, 12_000)).is_met


def test_нет_минут_нет_данных_и_норма_не_выполнена() -> None:
    progress = _progress([])

    assert not progress.has_data
    assert not progress.is_met


def test_шаги_дня_это_все_шаги_а_не_только_в_минуты_ходьбы() -> None:
    assert _progress([minute(0, steps=30), minute(1, steps=30)]).steps == 60


def test_минуты_ходьбы_без_пульса_считаются_отдельно() -> None:
    progress = _progress(_day(10, 0, gaps=3))

    assert progress.walking_minutes == 13
    assert progress.walking_minutes_without_heart_rate == 3


def test_незакончившийся_день_не_окончательный() -> None:
    assert not _progress(_day(35, 11_000), is_final=False).is_final
