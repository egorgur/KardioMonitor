"""Провайдеры dishka уровня приложения: настройки, часы, cookie сеанса врача; подписчики
Мониторинга из верхних контекстов (ADR-0009)."""

from dishka import Provider, Scope, provide

from kardiomonitor.app.settings import Settings
from kardiomonitor.features.access.api import SessionCookie
from kardiomonitor.features.alerts.high_heart_rate import HighHeartRateWatch
from kardiomonitor.features.alerts.training_minutes import TrainingMinutesWatch
from kardiomonitor.features.monitoring.domain.monitoring_events import (
    MonitoringSubscriber,
    MonitoringSubscribers,
)
from kardiomonitor.features.rehab.day_outcome_refresh import DayOutcomeRefresh
from kardiomonitor.features.rehab.domain.plan_changed import PlanChanged
from kardiomonitor.platform.clock import SystemClock
from kardiomonitor.shared_kernel import Clock


class AppProvider(Provider):
    """Объекты на всё время работы сервера."""

    scope = Scope.APP

    def __init__(self, settings: Settings) -> None:
        super().__init__()
        self._settings = settings

    @provide
    def settings(self) -> Settings:
        return self._settings

    @provide
    def clock(self) -> Clock:
        return SystemClock()

    @provide
    def session_cookie(self) -> SessionCookie:
        return SessionCookie(is_secure=self._settings.secure_cookies)


class MonitoringReactionsProvider(Provider):
    """Один подписчик Мониторинга из подписчиков верхних контекстов: Мониторинг о них не знает."""

    scope = Scope.REQUEST

    high_heart_rate = provide(HighHeartRateWatch)
    day_outcome_refresh = provide(DayOutcomeRefresh)
    training_minutes = provide(TrainingMinutesWatch)

    @provide
    def plan_changed(self, training_minutes: TrainingMinutesWatch) -> PlanChanged:
        return training_minutes

    @provide
    def subscriber(
        self,
        day_outcome_refresh: DayOutcomeRefresh,
        high_heart_rate: HighHeartRateWatch,
        training_minutes: TrainingMinutesWatch,
    ) -> MonitoringSubscriber:
        return MonitoringSubscribers((day_outcome_refresh, high_heart_rate, training_minutes))
