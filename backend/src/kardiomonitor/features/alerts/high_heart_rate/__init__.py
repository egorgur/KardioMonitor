"""Слайс «Высокий пульс»: проверка принятых минут и «пульса сейчас» (ТР-08). Без HTTP — точка
входа подписчик Мониторинга, его подключает `app` (ADR-0009)."""

from .high_heart_rate_watch import HighHeartRateWatch

__all__ = ["HighHeartRateWatch"]
