"""Провайдер dishka уровня приложения: настройки, часы, доступ к панели."""

from dishka import Provider, Scope, provide

from kardiomonitor.app.settings import Settings
from kardiomonitor.platform.clock import SystemClock
from kardiomonitor.platform.web.dashboard_access import DashboardAccessPolicy
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
    def dashboard_access(self) -> DashboardAccessPolicy:
        key = self._settings.dashboard_key
        # Пустое значение в .env — «ключа нет», а не ключ из пустой строки.
        return DashboardAccessPolicy(None if key is None else key.get_secret_value() or None)
