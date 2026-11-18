package ru.kardiomonitor.features.monitoring.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class PatientDayRangeTest {
    private val berlin = ZoneId.of("Europe/Berlin")

    @Test
    fun `день перехода на летнее время содержит 23 часа`() {
        assertEquals(23L * 60 * 60, durationSeconds("2026-03-29"))
    }

    @Test
    fun `день перехода на зимнее время содержит 25 часов`() {
        assertEquals(25L * 60 * 60, durationSeconds("2026-10-25"))
    }

    private fun durationSeconds(day: String): Long {
        val range = LocalDate.parse(day).patientDayRange(berlin)
        return range.toEpochSecond - range.fromEpochSecond
    }
}
