"""День пациента — в его часовом поясе."""

from datetime import UTC, date, datetime
from zoneinfo import ZoneInfo

from kardiomonitor.features.monitoring.domain.patient_day import PatientDay

NOVOSIBIRSK = ZoneInfo("Asia/Novosibirsk")


def test_день_пациента_начинается_в_полночь_по_его_часовому_поясу() -> None:
    day = PatientDay(date(2026, 10, 15), NOVOSIBIRSK)

    assert day.start == datetime(2026, 10, 14, 17, 0, tzinfo=UTC)
    assert day.end == datetime(2026, 10, 15, 17, 0, tzinfo=UTC)


def test_минута_2359_по_новосибирску_относится_к_своему_дню_а_не_к_следующему_по_utc() -> None:
    minute_start = datetime(2026, 10, 15, 23, 59, tzinfo=NOVOSIBIRSK)
    day = PatientDay(date(2026, 10, 15), NOVOSIBIRSK)

    assert day.start <= minute_start < day.end
