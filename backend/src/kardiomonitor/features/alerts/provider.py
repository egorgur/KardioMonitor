"""Провайдер dishka контекста «Оповещения»: реализации портов (ADR-0003)."""

from dishka import Provider, Scope, provide

from kardiomonitor.features.alerts.data.sql_alert_journal import SqlAlertJournal
from kardiomonitor.features.alerts.domain.alert_journal import AlertJournal


class AlertsProvider(Provider):
    """Реализации портов контекста — только здесь знают о `data/`."""

    scope = Scope.REQUEST

    journal = provide(SqlAlertJournal, provides=AlertJournal)
