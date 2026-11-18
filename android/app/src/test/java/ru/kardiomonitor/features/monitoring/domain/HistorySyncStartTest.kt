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
        assertEquals(Instant.parse("2026-10-09T10:01:00Z"), HistorySyncStart.after(last, pairedAt = null, now = now).toInstant())
    }

    @Test
    fun `браслет, привязанный до запоминания момента привязки, отдаёт историю за 7 дней`() {
        assertEquals(now.minusDays(7), HistorySyncStart.after(lastRecordedMinute = null, pairedAt = null, now = now))
    }

    @Test
    fun `время начала передаётся браслету в часовом поясе телефона`() {
        val last = Instant.parse("2026-10-09T10:00:00Z")
        assertEquals(now.zone, HistorySyncStart.after(last, pairedAt = null, now = now).zone)
    }

    @Test
    fun `первая синхронизация начинается с минуты привязки браслета`() {
        val pairedAt = Instant.parse("2026-10-08T09:15:42Z")
        assertEquals(
            Instant.parse("2026-10-08T09:15:00Z"),
            HistorySyncStart.after(lastRecordedMinute = null, pairedAt = pairedAt, now = now).toInstant(),
        )
    }

    @Test
    fun `минуты прежнего пациента до привязки не выгружаются, даже если история на телефоне старше`() {
        val lastOfPreviousBand = Instant.parse("2026-10-05T20:00:00Z")
        val pairedAt = Instant.parse("2026-10-08T09:15:00Z")
        assertEquals(pairedAt, HistorySyncStart.after(lastOfPreviousBand, pairedAt, now).toInstant())
    }

    @Test
    fun `после привязки синхронизация продолжается с минуты после последней сохранённой`() {
        val pairedAt = Instant.parse("2026-10-08T09:15:00Z")
        val last = Instant.parse("2026-10-09T10:00:00Z")
        assertEquals(Instant.parse("2026-10-09T10:01:00Z"), HistorySyncStart.after(last, pairedAt, now).toInstant())
    }

    @Test
    fun `давняя привязка не расширяет первую выгрузку дальше 7 дней`() {
        val pairedAt = now.minusDays(30).toInstant()
        assertEquals(now.minusDays(7), HistorySyncStart.after(lastRecordedMinute = null, pairedAt = pairedAt, now = now))
    }
}
