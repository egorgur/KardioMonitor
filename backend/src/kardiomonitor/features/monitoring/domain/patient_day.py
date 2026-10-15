"""День пациента — календарный день в его часовом поясе."""

from dataclasses import dataclass
from datetime import date, datetime, time, timedelta
from zoneinfo import ZoneInfo


@dataclass(frozen=True, slots=True)
class PatientDay:
    """Границы дня считаются в часовом поясе пациента, а не сервера и не UTC."""

    day: date
    time_zone: ZoneInfo

    @property
    def start(self) -> datetime:
        return datetime.combine(self.day, time(), tzinfo=self.time_zone)

    @property
    def end(self) -> datetime:
        """Начало следующего дня (не включается)."""
        return datetime.combine(self.day + timedelta(days=1), time(), tzinfo=self.time_zone)
