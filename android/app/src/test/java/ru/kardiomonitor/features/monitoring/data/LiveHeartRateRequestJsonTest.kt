package ru.kardiomonitor.features.monitoring.data

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.kardiomonitor.features.monitoring.domain.LiveHeartRateRequest
import java.time.Instant

class LiveHeartRateRequestJsonTest {
    @Test
    fun `запрос начало и упорядоченный outbox переживают перезапуск процесса`() {
        val now = Instant.parse("2026-11-01T09:00:00Z")
        val request = LiveHeartRateRequest(
            id = "request-1",
            durationMinutes = 10,
            requestedAt = now,
            expiresAt = now.plusSeconds(7_200),
        ).withReport(
            LiveHeartRateRequest.Report(LiveHeartRateRequest.Status.Running),
            now.plusSeconds(60),
        ).withReport(
            LiveHeartRateRequest.Report(LiveHeartRateRequest.Status.Completed),
            now.plusSeconds(660),
        )

        val restored = LiveHeartRateRequestJson.decode(LiveHeartRateRequestJson.encode(request))

        assertEquals(request, restored)
        assertEquals(
            listOf(LiveHeartRateRequest.Status.Running, LiveHeartRateRequest.Status.Completed),
            restored.reports.map { it.status },
        )
    }
}
