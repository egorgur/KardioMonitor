package ru.kardiomonitor.features.monitoring.domain

import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * История активности — все сохранённые минуты. Одна запись на минуту: повторная выгрузка
 * перезаписывает минуту, а не дублирует её.
 */
interface ActivityHistory {
    /** Событие «история пополнилась»: [save] записал новые минуты. */
    val changes: Flow<Unit>

    suspend fun save(minutes: List<ActivityMinute>)

    /** Минуты календарного дня [day] в указанном часовом поясе, по порядку. */
    suspend fun minutesOf(day: LocalDate, timeZone: ZoneId = ZoneId.systemDefault()): List<ActivityMinute>

    /** Минуты включительного диапазона дней, сгруппированные в указанном часовом поясе. */
    suspend fun minutesBetween(
        firstDay: LocalDate,
        lastDay: LocalDate,
        timeZone: ZoneId,
    ): Map<LocalDate, List<ActivityMinute>>

    /** Начало последней сохранённой минуты; null — история пуста. */
    suspend fun lastRecordedMinute(): Instant?

    /** До [limit] минут, начавшихся позже [after] (null — с самой ранней), по порядку. */
    suspend fun minutesAfter(after: Instant?, limit: Int): List<ActivityMinute>
}
