package ru.kardiomonitor.features.monitoring.liveheartrate

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.kardiomonitor.features.clinic.domain.PhoneLiveHeartRateRequest
import ru.kardiomonitor.features.clinic.fakes.FakeClinicServer
import ru.kardiomonitor.features.clinic.fakes.FakeServerRegistrationRepository
import ru.kardiomonitor.features.monitoring.domain.HistorySyncEvents
import ru.kardiomonitor.features.monitoring.domain.LiveHeartRateRequest
import ru.kardiomonitor.features.monitoring.domain.LiveHeartRateRequestRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class LiveHeartRateRequestSynchronizerTest {
    private val now = Instant.parse("2026-11-01T09:00:00Z")
    private val server = FakeClinicServer()
    private val registrations = FakeServerRegistrationRepository(FakeClinicServer.REGISTRATION)
    private val requests = FakeRequestRepository()
    private val events = FakeHistorySyncEvents()
    private val scheduled = mutableListOf<String>()

    @Test
    fun `запрос забирается только после завершения попытки синхронизации`() = runBlocking {
        server.liveHeartRateRequest = remoteRequest()
        val synchronizer = synchronizer(this)
        val job = synchronizer.start()
        settleUntil { requests.selectedPatientId != null }

        assertNull(requests.request.value)
        events.publishAttemptCompleted()
        settleUntil { requests.request.value != null }

        assertEquals(1, server.activeLiveHeartRateRequestFetches)
        assertEquals("request-1", requests.request.value?.id)
        assertEquals(listOf("request-1"), scheduled)
        job.cancel()
    }

    @Test
    fun `неотправленные running и completed повторяются по порядку`() = runBlocking {
        requests.selectPatient(FakeClinicServer.REGISTRATION.patientId)
        requests.save(FakeClinicServer.REGISTRATION.patientId, remoteRequest().toLocal())
        val synchronizer = synchronizer(this)
        server.failure = "нет сети"

        synchronizer.report("request-1", LiveHeartRateRequest.Report(LiveHeartRateRequest.Status.Running))
        synchronizer.report("request-1", LiveHeartRateRequest.Report(LiveHeartRateRequest.Status.Completed))
        assertEquals(2, requests.request.value?.reports?.size)

        server.failure = null
        server.liveHeartRateRequest = remoteRequest()
        val job = synchronizer.start()
        repeat(10) { yield() }
        events.publishAttemptCompleted()
        settleUntil { server.liveHeartRateRequestReports.size == 2 }

        assertEquals(
            listOf(
                PhoneLiveHeartRateRequest.Status.Running,
                PhoneLiveHeartRateRequest.Status.Completed,
            ),
            server.liveHeartRateRequestReports.map { it.second.status },
        )
        assertEquals(emptyList<LiveHeartRateRequest.Report>(), requests.request.value?.reports)
        job.cancel()
    }

    private fun synchronizer(scope: CoroutineScope) = LiveHeartRateRequestSynchronizer(
        server,
        registrations,
        requests,
        events,
        scope,
        scheduled::add,
        Clock.fixed(now, ZoneOffset.UTC),
    )

    private suspend fun settleUntil(condition: () -> Boolean) = withTimeout(1_000) {
        while (!condition()) yield()
    }

    private fun remoteRequest() = PhoneLiveHeartRateRequest(
        requestId = "request-1",
        durationMinutes = 10,
        requestedAt = now,
        expiresAt = now.plusSeconds(7_200),
        status = PhoneLiveHeartRateRequest.Status.WaitingForPhone,
        startedAt = null,
        finishedAt = null,
        failureReason = null,
    )

    private fun PhoneLiveHeartRateRequest.toLocal() = LiveHeartRateRequest(
        id = requestId,
        durationMinutes = durationMinutes,
        requestedAt = requestedAt,
        expiresAt = expiresAt,
    )

    private class FakeHistorySyncEvents : HistorySyncEvents {
        private val events = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        override val attemptsCompleted = events
        override fun publishAttemptCompleted() { events.tryEmit(Unit) }
    }

    private class FakeRequestRepository : LiveHeartRateRequestRepository {
        private val current = MutableStateFlow<LiveHeartRateRequest?>(null)
        override val request: StateFlow<LiveHeartRateRequest?> = current
        var selectedPatientId: String? = null

        override fun selectPatient(patientId: String?) { selectedPatientId = patientId }
        override fun save(patientId: String, request: LiveHeartRateRequest) {
            require(patientId == selectedPatientId)
            current.value = request
        }
    }
}
