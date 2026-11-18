"""Провайдер dishka контекста «Мониторинг»: реализации портов (ADR-0003)."""

from dishka import Provider, Scope, provide

from kardiomonitor.features.monitoring.data.sql_activity_history import SqlActivityHistory
from kardiomonitor.features.monitoring.data.sql_live_heart_rate_log import SqlLiveHeartRateLog
from kardiomonitor.features.monitoring.data.sql_live_heart_rate_requests import (
    SqlLiveHeartRateRequests,
)
from kardiomonitor.features.monitoring.domain.activity_history import ActivityHistory
from kardiomonitor.features.monitoring.domain.live_heart_rate import LiveHeartRateLog
from kardiomonitor.features.monitoring.domain.live_heart_rate_request import LiveHeartRateRequests


class MonitoringProvider(Provider):
    """Реализации портов контекста — только здесь знают о `data/`."""

    scope = Scope.REQUEST

    activity_history = provide(SqlActivityHistory, provides=ActivityHistory)
    live_heart_rate_log = provide(SqlLiveHeartRateLog, provides=LiveHeartRateLog)
    live_heart_rate_requests = provide(SqlLiveHeartRateRequests, provides=LiveHeartRateRequests)
