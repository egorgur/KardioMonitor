package ru.kardiomonitor.features.monitoring.domain

import java.time.Instant

/**
 * Итог последней синхронизации истории.
 *
 * @property summary итог для пользователя: «загружено 30 мин», «ошибка: …».
 */
data class LastSync(val at: Instant, val summary: String, val isBackground: Boolean)
