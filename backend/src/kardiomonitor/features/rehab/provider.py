"""Провайдер dishka контекста «Реабилитация»: реализации портов (ADR-0003)."""

from dishka import Provider, Scope, provide

from kardiomonitor.features.rehab.data.sql_day_outcomes import SqlDayOutcomes
from kardiomonitor.features.rehab.data.sql_rehab_plans import SqlRehabPlans
from kardiomonitor.features.rehab.domain.day_outcomes import DayOutcomes
from kardiomonitor.features.rehab.domain.rehab_plan import RehabPlans
from kardiomonitor.features.rehab.rehab_plan.revise_plan import RevisePlan


class RehabProvider(Provider):
    """Реализации портов контекста — только здесь знают о `data/`."""

    scope = Scope.REQUEST

    plans = provide(SqlRehabPlans, provides=RehabPlans)
    day_outcomes = provide(SqlDayOutcomes, provides=DayOutcomes)
    revise_plan = provide(RevisePlan)
