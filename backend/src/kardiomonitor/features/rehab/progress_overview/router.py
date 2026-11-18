"""Точка входа слайса «Прогресс пациентов врача»."""

from typing import Self
from uuid import UUID

from dishka.integrations.fastapi import DishkaRoute, FromDishka
from fastapi import APIRouter, Depends

from kardiomonitor.features.access.api import session_scheme
from kardiomonitor.features.monitoring.domain.activity_history import ActivityHistory
from kardiomonitor.features.patients.api import AttendedPatients
from kardiomonitor.features.patients.domain.patient import PatientId
from kardiomonitor.features.rehab.domain.daily_target_progress import DailyTargetProgress
from kardiomonitor.features.rehab.domain.day_outcomes import DayOutcomes
from kardiomonitor.features.rehab.domain.rehab_plan import RehabPlans
from kardiomonitor.features.rehab.domain.rehab_progress import RehabProgress
from kardiomonitor.platform.web.api_model import ApiModel
from kardiomonitor.shared_kernel import Clock, Transaction

from .overview_progress import overview_progress

router = APIRouter(
    prefix="/api/v1",
    tags=["Панель клиники"],
    route_class=DishkaRoute,
    dependencies=[Depends(session_scheme)],
)


class TodayOut(ApiModel):
    training_minutes: int
    target_training_minutes: int
    steps: int
    target_steps: int
    is_met: bool

    @classmethod
    def from_domain(cls, progress: DailyTargetProgress) -> Self:
        return cls(
            training_minutes=progress.training_minutes,
            target_training_minutes=progress.targets.training_minutes,
            steps=progress.steps,
            target_steps=progress.targets.steps,
            is_met=progress.is_met,
        )


class PatientProgressOut(ApiModel):
    """Строка главной: «день X из Y», «в N днях из M», норма сегодня «пока»."""

    patient_id: UUID
    program_day: int | None
    duration_days: int
    is_finished: bool
    met_days: int
    completed_days: int
    today: TodayOut | None

    @classmethod
    def from_domain(cls, patient_id: PatientId, progress: RehabProgress) -> Self:
        return cls(
            patient_id=patient_id,
            program_day=progress.program_day,
            duration_days=progress.duration_days,
            is_finished=progress.is_finished,
            met_days=progress.met_days,
            completed_days=progress.completed_days,
            today=None if progress.today is None else TodayOut.from_domain(progress.today),
        )


@router.get("/rehab-progress", summary="Прогресс реабилитации всех пациентов врача")
async def list_rehab_progress(
    patients: FromDishka[AttendedPatients],
    plans: FromDishka[RehabPlans],
    outcomes: FromDishka[DayOutcomes],
    history: FromDishka[ActivityHistory],
    clock: FromDishka[Clock],
    transaction: FromDishka[Transaction],
) -> list[PatientProgressOut]:
    """По кешу итогов дня (ТР-06, п. 4): минуты читаются только за дни без итога."""
    attended = await patients.all()
    progress = await overview_progress(
        attended,
        now=clock.now(),
        plans=plans,
        outcomes=outcomes,
        history=history,
        transaction=transaction,
    )
    return [
        PatientProgressOut.from_domain(patient.id, row)
        for patient, row in zip(attended, progress, strict=True)
    ]
