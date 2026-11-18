package ru.kardiomonitor.features.alerts.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.kardiomonitor.features.alerts.domain.PatientAlert
import java.time.Instant
import java.time.LocalDate

class PatientAlertTextTest {
    @Test
    fun `превышение сообщает день минуты и сохранённый порог одинаково для списка и уведомления`() {
        val alert = PatientAlert(
            "alert-training",
            PatientAlert.Kind.TrainingMinutesExceeded,
            LocalDate.parse("2026-11-12"),
            Instant.parse("2026-11-12T09:00:00Z"),
            PatientAlert.Details.TrainingMinutesExceeded(61, 60),
        )

        assertEquals("Превышен порог тренировочных минут", PatientAlertText.title(alert))
        assertEquals("За 12.11.2026: 61 мин тренировки при заданном пороге 60 мин", PatientAlertText.message(alert))
    }

    @Test
    fun `текст высокого пульса сообщает значения без диагноза и экстренного призыва`() {
        val alert = PatientAlert(
            "alert-1",
            PatientAlert.Kind.HighHeartRate,
            null,
            Instant.parse("2026-10-12T07:20:00Z"),
            PatientAlert.Details.HighHeartRate(
                Instant.parse("2026-10-12T07:01:00Z"),
                Instant.parse("2026-10-12T07:06:00Z"),
                6,
                140,
                130,
            ),
        )

        val text = PatientAlertText.title(alert) + " " + PatientAlertText.message(alert)

        assertTrue(text.contains("140"))
        assertTrue(text.contains("130"))
        assertFalse(text.contains("диагноз", ignoreCase = true))
        assertFalse(text.contains("срочно", ignoreCase = true))
    }
}
