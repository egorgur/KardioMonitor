"""Пульс."""

from dataclasses import dataclass

from kardiomonitor.shared_kernel import DomainRuleViolation

MIN_BPM = 11
MAX_BPM = 249


@dataclass(frozen=True, slots=True, order=True)
class HeartRate:
    """Пульс, уд/мин, 11–249 — как в Android. «Замера нет» — None, а не HeartRate."""

    bpm: int

    def __post_init__(self) -> None:
        if not MIN_BPM <= self.bpm <= MAX_BPM:
            raise DomainRuleViolation(
                f"пульс вне диапазона {MIN_BPM}–{MAX_BPM}: {self.bpm}",
                code="heart_rate_out_of_range",
            )
