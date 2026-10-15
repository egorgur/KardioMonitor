package ru.kardiomonitor.features.clinic.minuteupload

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.kardiomonitor.features.clinic.domain.MinuteReport.ReportedKind
import ru.kardiomonitor.features.clinic.fakes.FakeClinicServer
import ru.kardiomonitor.features.monitoring.domain.ActivityKind
import ru.kardiomonitor.features.monitoring.domain.ActivityMinute
import ru.kardiomonitor.features.monitoring.domain.HeartRate
import ru.kardiomonitor.features.monitoring.domain.Intensity
import ru.kardiomonitor.features.monitoring.domain.StepCount
import ru.kardiomonitor.features.monitoring.fakes.FakeActivityHistory
import java.io.IOException
import java.time.Instant

class UploadNewMinutesTest {
    private val dayStart = Instant.parse("2026-10-15T00:00:00Z")
    private val server = FakeClinicServer()
    private val registration = FakeClinicServer.REGISTRATION

    private fun minute(index: Int, kind: ActivityKind = ActivityKind.Other(code = 80), heartRate: Int? = 70) =
        ActivityMinute(
            start = dayStart.plusSeconds(index * 60L),
            steps = StepCount(index),
            heartRate = heartRate?.let(::HeartRate),
            kind = kind,
            intensity = Intensity(10),
        )

    @Test
    fun `врачу уходят все минуты истории, если сервер ещё ничего не получал`() = runBlocking {
        val history = FakeActivityHistory((0 until 3).map { minute(it) })

        val sent = UploadNewMinutes(server, history)(registration)

        assertEquals(3, sent)
        assertEquals(history.minutes.map { it.start }, server.minutes.keys.toList())
    }

    @Test
    fun `повторная отправка уносит только минуты после принятых сервером`() = runBlocking {
        val history = FakeActivityHistory((0 until 3).map { minute(it) })
        UploadNewMinutes(server, history)(registration)

        history.save(listOf(minute(3), minute(4)))
        val sent = UploadNewMinutes(server, history)(registration)

        assertEquals(2, sent)
        assertEquals(5, server.minutes.size)
    }

    @Test
    fun `неделя истории уходит пакетами по суткам`() = runBlocking {
        val history = FakeActivityHistory((0 until 7 * 24 * 60).map { minute(it) })

        UploadNewMinutes(server, history)(registration)

        assertEquals(7, server.batches.size)
        assertEquals(setOf(UploadNewMinutes.BATCH_MINUTES), server.batches.map { it.size }.toSet())
    }

    @Test
    fun `код браслета уходит только для нерасшифрованного типа минуты`() = runBlocking {
        val history = FakeActivityHistory(listOf(minute(0, ActivityKind.NotWorn, heartRate = null), minute(1)))

        UploadNewMinutes(server, history)(registration)

        val (notWorn, other) = server.minutes.values.toList()
        assertEquals(ReportedKind.NotWorn, notWorn.kind)
        assertNull(notWorn.kindCode)
        assertNull(notWorn.heartRateBpm)
        assertEquals(ReportedKind.Other to 80, other.kind to other.kindCode)
    }

    @Test(expected = IOException::class)
    fun `сервер недоступен — сбой связи, а не падение`() {
        runBlocking {
            server.failure = "нет сети"
            UploadNewMinutes(server, FakeActivityHistory(listOf(minute(0))))(registration)
        }
    }
}
