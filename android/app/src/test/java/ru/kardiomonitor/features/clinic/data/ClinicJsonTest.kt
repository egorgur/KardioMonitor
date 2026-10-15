package ru.kardiomonitor.features.clinic.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.kardiomonitor.features.clinic.domain.MinuteReport
import ru.kardiomonitor.features.clinic.domain.MinuteReport.ReportedKind
import ru.kardiomonitor.features.clinic.domain.ServerAddress
import java.time.Instant

/** JSON совпадает с контрактом сервера (backend/docs/api-contract.md, `POST /api/v1/me/activity-minutes`). */
class ClinicJsonTest {
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
    fun `сервер без минут пациента отвечает null — отправка начинается с начала истории`() {
        assertNull(ClinicJson.uploadCursor(JSONObject("""{"started_at":null}""")))
        assertEquals(
            Instant.parse("2026-10-15T17:00:00Z"),
            ClinicJson.uploadCursor(JSONObject("""{"started_at":"2026-10-15T17:00:00Z"}""")),
        )
    }
}
