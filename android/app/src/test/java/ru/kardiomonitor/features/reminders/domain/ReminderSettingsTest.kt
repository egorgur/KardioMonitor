package ru.kardiomonitor.features.reminders.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.LocalTime

class ReminderSettingsTest {
    @Test
    fun `по умолчанию прогулка и норма включены а браслет выключен`() {
        val settings = ReminderSettings()

        assertEquals(true, settings.move.schedule.enabled)
        assertEquals(250, settings.move.stepsPerHour)
        assertEquals(LocalTime.of(18, 0), settings.dailyTarget.time)
        assertEquals(false, settings.wearBand.enabled)
    }

    @Test
    fun `окно не может переходить через полночь или быть короче часа`() {
        assertThrows(IllegalArgumentException::class.java) {
            ReminderWindow(LocalTime.of(20, 0), LocalTime.of(9, 0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReminderWindow(LocalTime.of(9, 0), LocalTime.of(9, 30))
        }
    }
}
