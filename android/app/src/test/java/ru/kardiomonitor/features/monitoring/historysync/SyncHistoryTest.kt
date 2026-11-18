package ru.kardiomonitor.features.monitoring.historysync

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.kardiomonitor.features.band.api.BandConnection
import ru.kardiomonitor.features.band.domain.ConnectionProblem
import ru.kardiomonitor.features.band.fakes.FakeBandConnection
import ru.kardiomonitor.features.monitoring.domain.ActivityKind
import ru.kardiomonitor.features.monitoring.domain.ActivityMinute
import ru.kardiomonitor.features.monitoring.domain.HeartRate
import ru.kardiomonitor.features.monitoring.domain.HistorySyncEvents
import ru.kardiomonitor.features.monitoring.domain.Intensity
import ru.kardiomonitor.features.monitoring.domain.StepCount
import ru.kardiomonitor.features.monitoring.fakes.FakeActivityHistory
import ru.kardiomonitor.features.monitoring.fakes.FakeActivitySource
import ru.kardiomonitor.features.monitoring.fakes.FakeSyncSettings
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.milliseconds

class SyncHistoryTest {
    private val now = Instant.parse("2026-10-09T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private val connection = FakeBandConnection()
    private val source = FakeActivitySource()
    private val history = FakeActivityHistory()
    private val settings = FakeSyncSettings()
    private val events = RecordingHistorySyncEvents()
    private var pairedAt: Instant? = null
    private val syncHistory = SyncHistory(connection, { pairedAt }, source, history, settings, clock, events)

    private fun minute(at: String, heartRate: Int? = 70) = ActivityMinute(
        start = Instant.parse(at),
        steps = StepCount(10),
        heartRate = heartRate?.let(::HeartRate),
        kind = ActivityKind.Other(code = 80),
        intensity = Intensity(20),
    )

    @Test
    fun `выгруженные с браслета минуты сохраняются в историю`() = runBlocking {
        source.recorded = listOf(minute("2026-10-09T10:00:00Z"), minute("2026-10-09T10:01:00Z"))

        val result = syncHistory(isBackground = false)

        assertEquals(SyncResult.Downloaded(minutes = 2), result)
        assertEquals(source.recorded, history.minutes)
    }

    @Test
    fun `повторная синхронизация не дублирует минуты`() = runBlocking {
        source.recorded = listOf(minute("2026-10-09T10:00:00Z"), minute("2026-10-09T10:01:00Z"))
        syncHistory(isBackground = false)

        source.recorded += minute("2026-10-09T10:02:00Z")
        val second = syncHistory(isBackground = true)

        assertEquals(SyncResult.Downloaded(minutes = 1), second)
        assertEquals(3, history.minutes.size)
        assertEquals(Instant.parse("2026-10-09T10:02:00Z"), source.requestedStarts.last().toInstant())
    }

    @Test
    fun `минуты, записанные на браслете до привязки, не сохраняются`() = runBlocking {
        source.recorded = listOf(minute("2026-10-09T09:58:00Z"), minute("2026-10-09T10:00:00Z"))
        pairedAt = Instant.parse("2026-10-09T09:59:30Z")

        val result = syncHistory(isBackground = false)

        assertEquals(SyncResult.Downloaded(minutes = 1), result)
        assertEquals(listOf(minute("2026-10-09T10:00:00Z")), history.minutes)
    }

    @Test
    fun `итог синхронизации записывается с источником запуска`() = runBlocking {
        syncHistory(isBackground = true)

        val last = settings.lastSync.value
        assertEquals("новых данных нет", last?.summary)
        assertEquals(true, last?.isBackground)
        assertEquals(now, last?.at)
        assertEquals(listOf(true), connection.sessions)
        assertEquals(1, events.completed)
    }

    @Test
    fun `недоступный браслет — итог синхронизации называет причину`() = runBlocking {
        connection.unavailable = ConnectionProblem.NotFound

        val result = syncHistory(isBackground = true)

        assertTrue(result is SyncResult.Failed)
        assertTrue(settings.lastSync.value?.summary.orEmpty().startsWith("ошибка: "))
        assertTrue(history.minutes.isEmpty())
        assertEquals(1, events.completed)
    }

    @Test
    fun `зависший браслет — фоновая синхронизация завершается ошибкой, а не тихой отменой`() = runBlocking {
        val hangingConnection = object : BandConnection by connection {
            override suspend fun <T> withSession(isBackground: Boolean, block: suspend () -> T): T = awaitCancellation()
        }
        val sync = SyncHistory(hangingConnection, { pairedAt }, source, history, settings, clock, events)

        val result = sync(isBackground = true, timeout = 10.milliseconds)

        assertTrue(result is SyncResult.Failed)
        assertTrue(settings.lastSync.value?.summary.orEmpty().startsWith("ошибка: синхронизация не уложилась"))
        assertEquals(1, events.completed)
    }

    @Test
    fun `неподтверждённая история не открывает фон и не продвигает последнюю минуту`() = runBlocking {
        val existing = minute("2026-10-09T10:00:00Z")
        history.save(listOf(existing))
        val unavailable = object : ru.kardiomonitor.features.monitoring.domain.ActivitySource by source {
            override val monitoringUnavailableReason: String = "Band 9: история не подтверждена"
        }
        val sync = SyncHistory(connection, { pairedAt }, unavailable, history, settings, clock, events)

        val result = sync(isBackground = true)

        assertTrue(result is SyncResult.Failed)
        assertEquals(listOf(existing), history.minutes)
        assertTrue(connection.sessions.isEmpty())
        assertTrue(source.requestedStarts.isEmpty())
        assertTrue(settings.lastSync.value?.summary.orEmpty().contains("не подтверждена"))
    }

    private class RecordingHistorySyncEvents : HistorySyncEvents {
        override val attemptsCompleted: Flow<Unit> = emptyFlow()
        var completed = 0
        override fun publishAttemptCompleted() { completed += 1 }
    }
}
