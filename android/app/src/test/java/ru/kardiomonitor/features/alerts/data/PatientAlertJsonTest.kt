package ru.kardiomonitor.features.alerts.data

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.kardiomonitor.features.alerts.domain.PatientAlert
import java.time.Instant
import java.time.LocalDate

class PatientAlertJsonTest {
    @Test
    fun `оповещения курсор и отметки показа переживают JSON round trip`() {
        val expected = PatientAlertArchive(
            alerts = listOf(highHeartRate(), dailyTarget(), lowActivity(), trainingMinutesExceeded()),
            cursor = 17,
            notifiedIds = setOf("alert-high", "alert-daily", "alert-training"),
        )

        val restored = PatientAlertJson.decodeArchive(PatientAlertJson.encodeArchive(expected))

        assertEquals(expected, restored)
    }

    private fun highHeartRate(): PatientAlert = PatientAlert(
        "alert-high",
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

    private fun dailyTarget(): PatientAlert = PatientAlert(
        "alert-daily",
        PatientAlert.Kind.DailyTargetMissed,
        LocalDate.parse("2026-10-12"),
        Instant.parse("2026-10-12T17:00:00Z"),
        PatientAlert.Details.DailyTarget(12, 30, 6_200, 10_000),
    )

    private fun trainingMinutesExceeded(): PatientAlert = PatientAlert(
        "alert-training",
        PatientAlert.Kind.TrainingMinutesExceeded,
        LocalDate.parse("2026-11-12"),
        Instant.parse("2026-11-12T09:00:00Z"),
        PatientAlert.Details.TrainingMinutesExceeded(61, 60),
    )

    private fun lowActivity(): PatientAlert = PatientAlert(
        "alert-low",
        PatientAlert.Kind.LowActivity,
        LocalDate.parse("2026-10-12"),
        Instant.parse("2026-10-12T17:00:00Z"),
        PatientAlert.Details.LowActivity(400, 1_000),
    )
}
