package ru.kardiomonitor.features.monitoring.domain

import java.time.Instant
import java.time.ZonedDateTime

/**
 * С какой минуты запрашивать историю у браслета: со следующей после последней сохранённой,
 * а при первой синхронизации — за [FIRST_SYNC_DAYS] дней.
 */
object HistorySyncStart {
    const val FIRST_SYNC_DAYS = 7L

    fun after(lastRecordedMinute: Instant?, now: ZonedDateTime): ZonedDateTime =
        lastRecordedMinute?.plusSeconds(60)?.atZone(now.zone) ?: now.minusDays(FIRST_SYNC_DAYS)
}
