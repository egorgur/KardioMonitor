package ru.kardiomonitor.features.clinic.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.kardiomonitor.features.clinic.domain.PhoneAlertReport
import java.time.Instant
import java.time.LocalDate

class ClinicAlertJsonTest {
    @Test
    fun `превышение читается как дневной снимок минут и порога`() {
        val feed = ClinicJson.patientAlerts(page(TRAINING_ALERT))
        val alert = feed.alerts.single()

        assertEquals("alert-training", alert.alertId)
        assertEquals(PhoneAlertReport.Kind.TrainingMinutesExceeded, alert.kind)
        assertEquals(LocalDate.parse("2026-11-12"), alert.day)
        assertEquals(Instant.parse("2026-11-12T09:00:00Z"), alert.createdAt)
        assertEquals(PhoneAlertReport.TrainingMinutesExceeded(61, 60), alert.trainingMinutesExceeded)
        assertNull(alert.highHeartRate)
        assertNull(alert.dailyTarget)
        assertNull(alert.lowActivity)
    }

    @Test
    fun `неизвестные виды между известными не мешают чтению и продвижению курсора`() {
        val feed = ClinicJson.patientAlerts(page("$UNKNOWN_ALERT,$TRAINING_ALERT,$LOW_ALERT,$UNKNOWN_ALERT"))

        assertEquals(listOf("alert-training", "alert-low"), feed.alerts.map { it.alertId })
        assertEquals(25L, feed.cursor)
        assertTrue(feed.hasMore)
        assertNull(feed.alerts.last().trainingMinutesExceeded)
    }

    @Test
    fun `страница только неизвестных видов сохраняет серверный курсор и признак продолжения`() {
        val feed = ClinicJson.patientAlerts(page(UNKNOWN_ALERT))

        assertTrue(feed.alerts.isEmpty())
        assertEquals(25L, feed.cursor)
        assertTrue(feed.hasMore)
    }

    @Test
    fun `последняя страница неизвестных видов завершает чтение`() {
        val feed = ClinicJson.patientAlerts(page(UNKNOWN_ALERT).put("has_more", false))

        assertTrue(feed.alerts.isEmpty())
        assertEquals(25L, feed.cursor)
        assertFalse(feed.hasMore)
    }

    private fun page(alerts: String): JSONObject =
        JSONObject("""{"alerts":[$alerts],"cursor":25,"has_more":true}""")

    private companion object {
        const val UNKNOWN_ALERT = """{"kind":"future_kind","future_payload":{"different":true}}"""
        const val TRAINING_ALERT = """{
            "alert_id":"alert-training","kind":"training_minutes_exceeded","day":"2026-11-12",
            "created_at":"2026-11-12T09:00:00Z","high_heart_rate":null,"daily_target":null,"low_activity":null,
            "training_minutes_exceeded":{"training_minutes":61,"threshold_minutes":60}
        }"""
        const val LOW_ALERT = """{
            "alert_id":"alert-low","kind":"low_activity","day":"2026-11-12","created_at":"2026-11-12T17:00:00Z",
            "high_heart_rate":null,"daily_target":null,"low_activity":{"steps":400,"threshold_steps":1000}
        }"""
    }
}
