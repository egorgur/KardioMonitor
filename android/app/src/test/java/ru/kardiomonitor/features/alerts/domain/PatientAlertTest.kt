package ru.kardiomonitor.features.alerts.domain

import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class PatientAlertTest {
    @Test
    fun `дневное оповещение обязательно относится ко дню пациента`() {
        assertThrows(IllegalArgumentException::class.java) {
            PatientAlert(
                "alert-1",
                PatientAlert.Kind.DailyTargetMet,
                null,
                Instant.parse("2026-10-12T17:00:00Z"),
                PatientAlert.Details.DailyTarget(35, 30, 11_000, 10_000),
            )
        }
    }

    @Test
    fun `превышение без дня пациента недопустимо`() {
        assertThrows(IllegalArgumentException::class.java) {
            PatientAlert(
                "alert-training",
                PatientAlert.Kind.TrainingMinutesExceeded,
                null,
                Instant.parse("2026-11-12T09:00:00Z"),
                PatientAlert.Details.TrainingMinutesExceeded(61, 60),
            )
        }
    }

    @Test
    fun `снимок превышения требует минуты строго выше допустимого порога`() {
        listOf(59, 60).forEach { minutes ->
            assertThrows(IllegalArgumentException::class.java) {
                PatientAlert.Details.TrainingMinutesExceeded(minutes, thresholdMinutes = 60)
            }
        }
        listOf(0, 1441).forEach { threshold ->
            assertThrows(IllegalArgumentException::class.java) {
                PatientAlert.Details.TrainingMinutesExceeded(1442, threshold)
            }
        }
    }

    @Test
    fun `значения другого вида не создают оповещение`() {
        assertThrows(IllegalArgumentException::class.java) {
            PatientAlert(
                "alert-1",
                PatientAlert.Kind.LowActivity,
                LocalDate.parse("2026-10-12"),
                Instant.parse("2026-10-12T17:00:00Z"),
                PatientAlert.Details.DailyTarget(12, 30, 400, 10_000),
            )
        }
    }
}
