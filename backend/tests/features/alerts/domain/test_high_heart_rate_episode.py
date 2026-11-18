"""Эпизоды высокого пульса (ТР-08, п. 5): критерии приёмки, порог 130."""

from datetime import UTC, datetime, timedelta

from kardiomonitor.features.alerts.domain.high_heart_rate_episode import (
    HeartRatePoint,
    HighHeartRateEpisode,
    high_heart_rate_episodes,
)
from kardiomonitor.features.monitoring.domain.heart_rate import HeartRate

TEN = datetime(2026, 10, 12, 7, 0, tzinfo=UTC)
"""10:00 у пациента в UTC+3."""


def _points(*minutes_and_bpm: tuple[int, int]) -> list[HeartRatePoint]:
    return [
        HeartRatePoint(TEN + timedelta(minutes=m), HeartRate(bpm)) for m, bpm in minutes_and_bpm
    ]


def _episodes(points: list[HeartRatePoint], threshold: int = 130) -> list[HighHeartRateEpisode]:
    return high_heart_rate_episodes(points, lambda _moment: threshold)


def test_пульс_135_140_138_и_131_через_перерыв_2_минуты_один_эпизод_до_140_за_6_минут() -> None:
    episodes = _episodes(_points((1, 135), (2, 140), (3, 138), (6, 131)))

    assert len(episodes) == 1
    assert episodes[0].max_heart_rate == HeartRate(140)
    assert episodes[0].duration_minutes == 6
    assert episodes[0].threshold_bpm == 130


def test_перерыв_ровно_5_минут_не_разрывает_эпизод() -> None:
    episodes = _episodes(_points((1, 140), (7, 140)))

    assert len(episodes) == 1


def test_перерыв_больше_5_минут_начинает_новый_эпизод() -> None:
    episodes = _episodes(_points((1, 140), (8, 140)))

    assert [episode.started_at for episode in episodes] == [
        TEN + timedelta(minutes=1),
        TEN + timedelta(minutes=8),
    ]


def test_пульс_ровно_на_пороге_не_высокий() -> None:
    assert _episodes(_points((1, 130), (2, 129))) == []


def test_нормальный_пульс_между_высокими_не_разрывает_эпизод_если_перерыв_короткий() -> None:
    episodes = _episodes(_points((1, 140), (2, 90), (3, 95), (4, 150)))

    assert len(episodes) == 1
    assert episodes[0].max_heart_rate == HeartRate(150)


def test_замеры_пульса_сейчас_раз_в_3_секунды_один_эпизод() -> None:
    points = [HeartRatePoint(TEN + timedelta(seconds=3 * i), HeartRate(140)) for i in range(40)]

    episodes = _episodes(points)

    assert len(episodes) == 1
    assert episodes[0].duration_minutes == 2


def test_порог_берётся_на_момент_замера() -> None:
    def threshold_at(moment: datetime) -> int:
        return 130 if moment < TEN + timedelta(minutes=5) else 160

    episodes = high_heart_rate_episodes(_points((1, 140), (10, 140)), threshold_at)

    assert [episode.started_at for episode in episodes] == [TEN + timedelta(minutes=1)]


def test_эпизоды_через_короткий_перерыв_объединяются_в_один() -> None:
    first, second = (
        _episodes(_points((1, 140), (3, 145)))[0],
        _episodes(_points((6, 150), (9, 135)))[0],
    )

    joined = first.joined(second)

    assert first.continues(second)
    assert (joined.started_at, joined.last_at) == (first.started_at, second.last_at)
    assert joined.max_heart_rate == HeartRate(150)
    assert joined.duration_minutes == 9
