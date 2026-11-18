"""Слайс «Пересчёт итогов дня»: минуты пришли — итоги их дней в кеше обновлены (ТР-06, п. 4).
Без HTTP — точка входа подписчик Мониторинга, его подключает `app` (ADR-0009)."""

from .day_outcome_refresh import DayOutcomeRefresh

__all__ = ["DayOutcomeRefresh"]
