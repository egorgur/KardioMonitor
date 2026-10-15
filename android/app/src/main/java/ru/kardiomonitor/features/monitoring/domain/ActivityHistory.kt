package ru.kardiomonitor.features.monitoring.domain

import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate

/**
 * История активности — все сохранённые минуты. Одна запись на минуту: повторная выгрузка
 * перезаписывает минуту, а не дублирует её.
 */
interface ActivityHistory {
    /** Событие «история пополнилась»: [save] записал новые минуты. */
    val changes: Flow<Unit>

    suspend fun save(minutes: List<ActivityMinute>)

    /** Минуты календарного дня [day] в часовом поясе телефона, по порядку. */
    suspend fun minutesOf(day: LocalDate): List<ActivityMinute>

    /** Начало последней сохранённой минуты; null — история пуста. */
    suspend fun lastRecordedMinute(): Instant?
}
