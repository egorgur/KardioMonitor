"""Распределение пульса по интервалам — ТР-04, критерии приёмки из docs/requirements.md."""

from kardiomonitor.features.monitoring.domain.heart_rate_distribution import (
    HeartRateBand,
    HeartRateDistribution,
)
from tests.features.monitoring.minutes import minute


def _counts(*heart_rates: int | None) -> dict[str, int]:
    minutes = [minute(m, heart_rate=hr) for m, hr in enumerate(heart_rates)]
    return {_label(band): band.minutes for band in HeartRateDistribution.of(minutes).bands}


def _label(band: HeartRateBand) -> str:
    if band.from_bpm is None:
        return f"<{band.to_bpm + 1 if band.to_bpm is not None else '?'}"
    return f"{band.from_bpm}+" if band.to_bpm is None else f"{band.from_bpm}–{band.to_bpm}"


def test_интервалы_по_10_уд_мин_от_ниже_40_до_120_и_выше() -> None:
    assert list(_counts()) == [
        "<40", "40–49", "50–59", "60–69", "70–79", "80–89", "90–99", "100–109", "110–119", "120+",
    ]  # fmt: skip


def test_минуты_раскладываются_по_интервалам() -> None:
    counts = _counts(45, 52, 58, 120, 135)

    assert counts["40–49"] == 1
    assert counts["50–59"] == 2
    assert counts["120+"] == 2
    assert sum(counts.values()) == 5


def test_левая_граница_интервала_включительно() -> None:
    counts = _counts(39, 50, 119)

    assert counts["<40"] == 1
    assert counts["50–59"] == 1
    assert counts["110–119"] == 1


def test_минуты_без_пульса_не_учитываются() -> None:
    assert sum(_counts(None, 100, None).values()) == 1
