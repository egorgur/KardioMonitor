package ru.kardiomonitor.features.monitoring.liveheartrate

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test
import ru.kardiomonitor.features.band.fakes.FakeBandConnection
import ru.kardiomonitor.features.monitoring.domain.ActivityMinute
import ru.kardiomonitor.features.monitoring.domain.ActivitySource
import ru.kardiomonitor.features.monitoring.domain.HeartRate
import ru.kardiomonitor.features.monitoring.domain.LiveHeartRateFeed
import ru.kardiomonitor.features.monitoring.domain.LiveHeartRateReading
import ru.kardiomonitor.features.monitoring.domain.StepCount
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime

class LiveHeartRateMeasurementTest {
    private val connection = FakeBandConnection()
    private val source = CountingActivitySource()
    private val feed = RecordingLiveHeartRateFeed()
    private val now = Instant.parse("2026-11-01T09:00:00Z")

    @Test
    fun `запрошенное измерение использует фоновый сеанс и общий feed`() = runBlocking {
        source.manualFirst = false
        val measurement = measurement(this)

        measurement.runRequested(durationSeconds = 1)

        assertEquals(listOf(true), connection.sessions)
        assertEquals(listOf(HeartRate(73)), feed.recorded.map { it.heartRate })
    }

    @Test
    fun `запрос клиники останавливает ручной поток до запуска второго`() = runBlocking {
        val measurement = measurement(this)
        measurement.toggleManual()
        waitUntil { source.active == 1 }

        measurement.runRequested(durationSeconds = 1)

        assertEquals(1, source.maxActive)
        assertEquals(listOf(true), connection.sessions)
        assertEquals(LiveHeartRateMeasurement.State(), measurement.state.value)
    }

    @Test
    fun `live с неподтверждённой остановкой не запускает сенсор и не публикует данные`() = runBlocking {
        val unavailable = object : ActivitySource by source {
            override val monitoringUnavailableReason: String = "Band 9: остановка live не подтверждена"
        }
        val measurement = LiveHeartRateMeasurement(connection, unavailable, feed, this)
        measurement.toggleManual()
        assertEquals("Band 9: остановка live не подтверждена", measurement.state.value.problem)
        try {
            measurement.runRequested(60)
            org.junit.Assert.fail("непроверенное измерение не должно начинаться")
        } catch (_: java.io.IOException) {
            assertEquals(0, source.calls)
            assertEquals(emptyList<Boolean>(), connection.sessions)
            assertEquals(emptyList<LiveHeartRateReading>(), feed.recorded)
        }
    }

    private fun measurement(scope: kotlinx.coroutines.CoroutineScope) = LiveHeartRateMeasurement(
        connection,
        source,
        feed,
        scope,
        Clock.fixed(now, ZoneOffset.UTC),
    )

    private suspend fun waitUntil(condition: () -> Boolean) = withTimeout(1_000) {
        while (!condition()) yield()
    }

    private class RecordingLiveHeartRateFeed : LiveHeartRateFeed {
        override val readings: Flow<LiveHeartRateReading> = emptyFlow()
        val recorded = mutableListOf<LiveHeartRateReading>()
        override fun publish(reading: LiveHeartRateReading) { recorded += reading }
    }

    private class CountingActivitySource : ActivitySource {
        var manualFirst = true
        var calls = 0
        var active = 0
        var maxActive = 0

        override fun liveHeartRate(): Flow<HeartRate?> = flow {
            calls += 1
            active += 1
            maxActive = maxOf(maxActive, active)
            try {
                if (manualFirst && calls == 1) awaitCancellation()
                emit(HeartRate(73))
            } finally {
                active -= 1
            }
        }

        override suspend fun minutesSince(
            start: ZonedDateTime,
            onProgress: (minutesReceived: Int) -> Unit,
        ): List<ActivityMinute> = emptyList()

        override fun stepsToday(): Flow<StepCount> = emptyFlow()
    }
}
