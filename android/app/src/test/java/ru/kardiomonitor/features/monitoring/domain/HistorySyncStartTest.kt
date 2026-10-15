package ru.kardiomonitor.features.monitoring.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime

class HistorySyncStartTest {
    private val now = ZonedDateTime.of(2026, 10, 9, 14, 30, 0, 0, ZoneOffset.ofHours(3))

    @Test
    fun `синхронизация начинается с минуты после последней сохранённой`() {
        val last = Instant.parse("2026-10-09T10:00:00Z")
        assertEquals(Instant.parse("2026-10-09T10:01:00Z"), HistorySyncStart.after(last, now).toInstant())
    }

    @Test
    fun `первая синхронизация забирает историю за 7 дней`() {
        assertEquals(now.minusDays(7), HistorySyncStart.after(lastRecordedMinute = null, now = now))
    }

    @Test
    fun `время начала передаётся браслету в часовом поясе телефона`() {
        val last = Instant.parse("2026-10-09T10:00:00Z")
        assertEquals(now.zone, HistorySyncStart.after(last, now).zone)
    }
}
