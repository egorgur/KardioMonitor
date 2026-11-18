package ru.kardiomonitor.features.clinic.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.kardiomonitor.features.clinic.domain.MinuteReport
import ru.kardiomonitor.features.clinic.domain.MinuteReport.ReportedKind
import ru.kardiomonitor.features.clinic.domain.EnrollmentCode
import ru.kardiomonitor.features.clinic.domain.ServerAddress
import ru.kardiomonitor.features.clinic.domain.PhoneAlertReport
import ru.kardiomonitor.features.clinic.domain.PhoneLiveHeartRateRequest
import ru.kardiomonitor.features.clinic.domain.LiveHeartRateRequestStatusReport
import java.time.Instant

/** JSON совпадает с контрактом сервера (backend/docs/api-contract.md, `POST /api/v1/me/activity-minutes`). */
class ClinicJsonTest {
    @Test
    fun `код привязки уходит единственным полем по контракту сервера`() {
        val json = ClinicJson.enrollmentRequest(EnrollmentCode("AbCdEfGhIjKlMnOpQrSt-_"))

        assertEquals(setOf("code"), json.keys().asSequence().toSet())
        assertEquals("AbCdEfGhIjKlMnOpQrSt-_", json.getString("code"))
    }

    @Test
    fun `минута уходит с полями контракта сервера, время — в UTC с секундами`() {
        val report = MinuteReport(Instant.parse("2026-10-15T03:01:00Z"), 54, 92, ReportedKind.Other, 80, 37)

        val json = ClinicJson.minute(report)

        assertEquals(
            setOf("started_at", "steps", "heart_rate", "kind", "kind_code", "intensity"),
            json.keys().asSequence().toSet(),
        )
        assertEquals("2026-10-15T03:01:00Z", json.getString("started_at"))
        assertEquals("other", json.getString("kind"))
        assertEquals(80, json.getInt("kind_code"))
    }

    @Test
    fun `минута без замера пульса уходит с null, а не с нулём`() {
        val report = MinuteReport(Instant.parse("2026-10-15T03:02:00Z"), 0, null, ReportedKind.NotWorn, null, 0)

        val json = ClinicJson.minute(report)

        assertTrue(json.isNull("heart_rate"))
        assertTrue(json.isNull("kind_code"))
        assertEquals("not_worn", json.getString("kind"))
    }

    @Test
    fun `ответ регистрации даёт токен и имя пациента`() {
        val response = JSONObject(
            """{"patient_id":"p-1","name":"Иван П.","time_zone":"Asia/Novosibirsk","token":"tkn"}""",
        )

        val registration = ClinicJson.registration(ServerAddress.parse("https://demo.ngrok-free.app"), response)

        assertEquals("tkn", registration.token.value)
        assertEquals("Иван П.", registration.patientName.value)
    }

    @Test
    fun `план реабилитации читается по телефонному контракту`() {
        val response = JSONObject(
            """{
                "time_zone":"Europe/Moscow",
                "effective_from":"2026-10-12",
                "walking_steps_threshold":45,
                "program":{"start_date":"2026-10-01","duration_days":25,"stages":[
                    {"first_day":1,"last_day":15,"targets":{"training_zone":{"lower_bpm":90,"upper_bpm":110},"training_minutes":30,"steps":10000}},
                    {"first_day":16,"last_day":25,"targets":{"training_zone":{"lower_bpm":95,"upper_bpm":115},"training_minutes":40,"steps":12000}}
                ]}
            }""".trimIndent(),
        )

        val plan = ClinicJson.rehabPlan(response)

        assertEquals("Europe/Moscow", plan.timeZone.id)
        assertEquals(45, plan.walkingStepsThreshold)
        assertEquals(listOf(1, 16), plan.stages.map { it.firstDay })
        assertEquals(40, plan.stages.last().trainingMinutes)
    }

    @Test
    fun `лента оповещений читается с курсором и значениями своего вида`() {
        val response = JSONObject(
            """{
                "alerts":[
                    {"alert_id":"alert-1","kind":"high_heart_rate","day":null,"created_at":"2026-10-12T07:20:00Z",
                     "high_heart_rate":{"started_at":"2026-10-12T07:01:00Z","last_at":"2026-10-12T07:06:00Z",
                     "duration_minutes":6,"max_heart_rate":140,"threshold_bpm":130},"daily_target":null,"low_activity":null},
                    {"alert_id":"alert-2","kind":"daily_target_missed","day":"2026-10-12","created_at":"2026-10-12T17:00:00Z",
                     "high_heart_rate":null,"daily_target":{"training_minutes":12,"target_training_minutes":30,
                     "steps":6200,"target_steps":10000},"low_activity":null}
                ],"cursor":17,"has_more":true
            }""".trimIndent(),
        )

        val feed = ClinicJson.patientAlerts(response)

        assertEquals(17, feed.cursor)
        assertTrue(feed.hasMore)
        assertEquals(PhoneAlertReport.Kind.HighHeartRate, feed.alerts.first().kind)
        assertEquals(140, feed.alerts.first().highHeartRate?.maxHeartRate)
        assertEquals(12, feed.alerts.last().dailyTarget?.trainingMinutes)
    }

    @Test
    fun `сервер без минут пациента отвечает null — отправка начинается с начала истории`() {
        assertNull(ClinicJson.uploadCursor(JSONObject("""{"started_at":null}""")))
        assertEquals(
            Instant.parse("2026-10-15T17:00:00Z"),
            ClinicJson.uploadCursor(JSONObject("""{"started_at":"2026-10-15T17:00:00Z"}""")),
        )
    }

    @Test
    fun `активный запрос пульса читается из envelope`() {
        val response = JSONObject(
            """{"request":{"request_id":"request-1","duration_minutes":10,
                "requested_at":"2026-11-01T09:00:00Z","expires_at":"2026-11-01T11:00:00Z",
                "status":"waiting_for_phone","started_at":null,"finished_at":null,
                "failure_reason":null}}""".trimIndent(),
        )

        val request = ClinicJson.liveHeartRateRequest(response)

        assertEquals("request-1", request?.requestId)
        assertEquals(PhoneLiveHeartRateRequest.Status.WaitingForPhone, request?.status)
        assertNull(request?.startedAt)
        assertNull(ClinicJson.liveHeartRateRequest(JSONObject("""{"request":null}""")))
    }

    @Test
    fun `отчёт failed уходит с точными полями и причиной`() {
        val report = LiveHeartRateRequestStatusReport(
            PhoneLiveHeartRateRequest.Status.Failed,
            PhoneLiveHeartRateRequest.FailureReason.NotificationPermissionDenied,
        )

        val json = ClinicJson.liveHeartRateRequestStatus(report)

        assertEquals(setOf("status", "failure_reason"), json.keys().asSequence().toSet())
        assertEquals("failed", json.getString("status"))
        assertEquals("notification_permission_denied", json.getString("failure_reason"))
    }
}
