"""План реабилитации пациента — версии программы и параметров наблюдения (ADR-0010)."""

from collections.abc import Collection, Sequence
from dataclasses import dataclass
from datetime import date
from typing import Protocol, Self

from kardiomonitor.features.monitoring.domain.activity_minute import ActivityMinute
from kardiomonitor.features.patients.domain.patient import Patient, PatientId
from kardiomonitor.features.rehab.domain.daily_target_progress import DailyTargetProgress
from kardiomonitor.features.rehab.domain.daily_targets import DailyTargets
from kardiomonitor.features.rehab.domain.monitoring_settings import (
    DEFAULT_MONITORING_SETTINGS,
    MonitoringSettings,
)
from kardiomonitor.features.rehab.domain.rehab_program import RehabProgram
from kardiomonitor.shared_kernel import DomainRuleViolation


@dataclass(frozen=True, slots=True)
class ProgramRevision:
    """Программа и параметры наблюдения, действующие с дня `effective_from` (день пациента)."""

    effective_from: date
    program: RehabProgram
    settings: MonitoringSettings


@dataclass(frozen=True, slots=True)
class RehabPlan:
    """Корень агрегата. Версии — по возрастанию дня начала действия, хотя бы одна.

    Правка не меняет версий раньше сегодняшнего дня: прошлые дни оцениваются нормами,
    которые действовали тогда (ТР-01, п. 8).
    """

    patient_id: PatientId
    revisions: tuple[ProgramRevision, ...]

    def __post_init__(self) -> None:
        days = [revision.effective_from for revision in self.revisions]
        if not days or days != sorted(set(days)):
            raise DomainRuleViolation(
                "у плана должна быть хотя бы одна версия, дни начала — по возрастанию без повторов",
                code="invalid_rehab_plan",
            )

    @classmethod
    def default_for(cls, patient: Patient) -> Self:
        """Программа по умолчанию с дня регистрации по часовому поясу пациента (ТР-01, п. 5)."""
        start = patient.day_of(patient.registered_at)
        revision = ProgramRevision(start, RehabProgram.default(start), DEFAULT_MONITORING_SETTINGS)
        return cls(patient.id, (revision,))

    @property
    def current(self) -> ProgramRevision:
        return self.revisions[-1]

    def revised(self, program: RehabProgram, settings: MonitoringSettings, today: date) -> Self:
        """Новая версия с сегодняшнего дня пациента; версия этого дня и более поздние заменяются."""
        kept = tuple(revision for revision in self.revisions if revision.effective_from < today)
        return type(self)(self.patient_id, (*kept, ProgramRevision(today, program, settings)))

    def revision_on(self, day: date) -> ProgramRevision:
        """Версия, действовавшая в день `day`; раньше первой версии — первая."""
        earlier = [revision for revision in self.revisions if revision.effective_from <= day]
        return earlier[-1] if earlier else self.revisions[0]

    def targets_on(self, day: date) -> DailyTargets | None:
        """Нормы дня по его версии; дня нет в программе своей версии — по текущей.

        None — дня нет и в текущей программе.
        """
        targets = self.revision_on(day).program.targets_on(day)
        return targets if targets is not None else self.current.program.targets_on(day)

    def progress_on(
        self, day: date, minutes: Sequence[ActivityMinute], *, is_final: bool
    ) -> DailyTargetProgress | None:
        """Выполнение нормы дня по нормам и порогу ходьбы его версии; None — день вне программы."""
        targets = self.targets_on(day)
        if targets is None:
            return None
        threshold = self.revision_on(day).settings.walking_steps_threshold
        return DailyTargetProgress.of(
            minutes, targets, walking_steps_threshold=threshold, is_final=is_final
        )


class RehabPlans(Protocol):
    """Сохранённые планы реабилитации."""

    async def of(self, patient_id: PatientId) -> RehabPlan | None:
        """План пациента; None — врач его не менял (действует `RehabPlan.default_for`)."""
        ...

    async def of_patients(self, patient_ids: Collection[PatientId]) -> dict[PatientId, RehabPlan]:
        """Сохранённые планы пациентов одним запросом; кто план не менял — того в ответе нет."""
        ...

    async def save(self, plan: RehabPlan) -> None:
        """Сохранить все версии плана; итоги дней пациента в кеше (`DayOutcomes`) сбросить."""
        ...


async def plan_of(patient: Patient, plans: RehabPlans) -> RehabPlan:
    """План пациента: сохранённый или программа по умолчанию (ТР-01, п. 5)."""
    return await plans.of(patient.id) or RehabPlan.default_for(patient)
