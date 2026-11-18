package ru.kardiomonitor.features.monitoring.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class LiveHeartRateRequestTest {
    private val now = Instant.parse("2026-11-01T09:00:00Z")

    @Test
    fun `running сохраняется до сети и повтор не дублирует outbox`() {
        val request = request()
        val running = LiveHeartRateRequest.Report(LiveHeartRateRequest.Status.Running)

        val queued = request.withReport(running, now).withReport(running, now.plusSeconds(10))

        assertEquals(LiveHeartRateRequest.Status.Running, queued.status)
        assertEquals(now, queued.startedAt)
        assertEquals(listOf(running), queued.reports)
    }

    @Test
    fun `отчёты подтверждаются строго с головы очереди`() {
        val running = LiveHeartRateRequest.Report(LiveHeartRateRequest.Status.Running)
        val completed = LiveHeartRateRequest.Report(LiveHeartRateRequest.Status.Completed)
        val request = request().withReport(running, now).withReport(completed, now.plusSeconds(600))

        val afterFirst = request.acknowledgeFirstReport()

        assertEquals(listOf(completed), afterFirst.reports)
        assertEquals(emptyList<LiveHeartRateRequest.Report>(), afterFirst.acknowledgeFirstReport().reports)
    }

    @Test
    fun `после перезапуска измерение продолжается только оставшееся время`() {
        val running = request().withReport(
            LiveHeartRateRequest.Report(LiveHeartRateRequest.Status.Running),
            now,
        )

        assertEquals(420, running.remainingSeconds(now.plusSeconds(180)))
        assertEquals(0, running.remainingSeconds(now.plusSeconds(700)))
        assertNull(request().startedAt)
    }

    private fun request(): LiveHeartRateRequest = LiveHeartRateRequest(
        id = "request-1",
        durationMinutes = 10,
        requestedAt = now,
        expiresAt = now.plusSeconds(7_200),
    )
}
