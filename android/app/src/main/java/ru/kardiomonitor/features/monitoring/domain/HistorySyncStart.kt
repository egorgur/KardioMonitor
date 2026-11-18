package ru.kardiomonitor.features.monitoring.domain

import java.time.Instant
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * С какой минуты запрашивать историю у браслета: со следующей после последней сохранённой,
 * а при первой синхронизации — за [FIRST_SYNC_DAYS] дней. Минуты раньше привязки браслета не
 * запрашиваются никогда: браслет клиники выдаётся повторно с тем же ключом, и на нём лежит
 * история прежнего пациента (ADR-0011).
 */
object HistorySyncStart {
    const val FIRST_SYNC_DAYS = 7L

    /** @param pairedAt момент привязки браслета; null — привязан до того, как приложение стало его запоминать. */
    fun after(lastRecordedMinute: Instant?, pairedAt: Instant?, now: ZonedDateTime): ZonedDateTime {
        val next = lastRecordedMinute?.plusSeconds(60)?.atZone(now.zone) ?: now.minusDays(FIRST_SYNC_DAYS)
        val pairedMinute = pairedAt?.truncatedTo(ChronoUnit.MINUTES)?.atZone(now.zone) ?: return next
        return maxOf(next, pairedMinute)
    }
}
