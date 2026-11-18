package ru.kardiomonitor.features.alerts.alertsync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.kardiomonitor.features.alerts.domain.PatientAlert
import ru.kardiomonitor.features.alerts.domain.PatientAlertNotifier
import ru.kardiomonitor.features.alerts.fakes.FakePatientAlertRepository
import ru.kardiomonitor.features.clinic.data.InMemoryClinicSyncEvents
import ru.kardiomonitor.features.clinic.domain.PhoneAlertFeed
import ru.kardiomonitor.features.clinic.domain.PhoneAlertReport
import ru.kardiomonitor.features.clinic.fakes.FakeClinicServer
import ru.kardiomonitor.features.clinic.fakes.FakeServerRegistrationRepository
import java.time.Instant
import java.time.LocalDate

class PatientAlertSynchronizerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun stopScope() = scope.cancel()

    @Test
    fun `после регистрации телефон забирает все страницы и показывает новые оповещения`() {
        val server = FakeClinicServer().apply {
            alertFeeds[0] = PhoneAlertFeed(listOf(dailyTarget("alert-1")), 1, hasMore = true)
            alertFeeds[1] = PhoneAlertFeed(listOf(lowActivity("alert-2")), 2, hasMore = false)
        }
        val repository = FakePatientAlertRepository()
        val notifier = FakePatientAlertNotifier()

        synchronizer(server, repository, notifier).start()

        assertEquals(listOf(0L, 1L), server.requestedAlertCursors)
        assertEquals(listOf("alert-1", "alert-2"), notifier.shown.map(PatientAlert::id))
        assertEquals(2, repository.alerts.value.size)
        assertEquals(2, repository.savedCursor)
    }

    @Test
    fun `превышение уведомляет один раз при повторе страницы отправке минут и перезапуске`() {
        val report = trainingMinutesExceeded()
        val server = FakeClinicServer().apply {
            alertFeeds[0] = PhoneAlertFeed(listOf(report), 1, hasMore = true)
            alertFeeds[1] = PhoneAlertFeed(listOf(report), 2, hasMore = false)
            alertFeeds[2] = PhoneAlertFeed(listOf(report), 3, hasMore = false)
            alertFeeds[3] = PhoneAlertFeed(emptyList(), 3, hasMore = false)
        }
        val events = InMemoryClinicSyncEvents()
        val repository = FakePatientAlertRepository()
        val notifier = FakePatientAlertNotifier()
        synchronizer(server, repository, notifier, events).start()

        events.publishMinuteUploadCompleted()
        synchronizer(server, repository, notifier).start()

        assertEquals(listOf("alert-training"), notifier.shown.map(PatientAlert::id))
        assertEquals(PatientAlert.Kind.TrainingMinutesExceeded, repository.alerts.value.single().kind)
        assertEquals(PatientAlert.Details.TrainingMinutesExceeded(61, 60), repository.alerts.value.single().details)
        assertEquals(LocalDate.parse("2026-11-12"), repository.alerts.value.single().day)
        assertEquals(3L, repository.savedCursor)
    }

    @Test
    fun `пустая после пропуска неизвестных видов страница не прерывает загрузку известных`() {
        val server = FakeClinicServer().apply {
            alertFeeds[0] = PhoneAlertFeed(emptyList(), 10, hasMore = true)
            alertFeeds[10] = PhoneAlertFeed(listOf(trainingMinutesExceeded()), 11, hasMore = true)
            alertFeeds[11] = PhoneAlertFeed(emptyList(), 12, hasMore = false)
        }
        val repository = FakePatientAlertRepository()
        val notifier = FakePatientAlertNotifier()
        val synchronizer = synchronizer(server, repository, notifier)

        synchronizer.start()

        assertEquals(listOf(0L, 10L, 11L), server.requestedAlertCursors)
        assertEquals(12L, repository.savedCursor)
        assertEquals(listOf("alert-training"), notifier.shown.map(PatientAlert::id))
        assertNull(synchronizer.state.value.problem)
    }

    @Test
    fun `изменённый эпизод обновляется но второй раз не уведомляет`() {
        val server = FakeClinicServer().apply {
            alertFeeds[0] = PhoneAlertFeed(listOf(highHeartRate("alert-1", 140)), 1, hasMore = false)
        }
        val events = InMemoryClinicSyncEvents()
        val repository = FakePatientAlertRepository()
        val notifier = FakePatientAlertNotifier()
        val synchronizer = synchronizer(server, repository, notifier, events)
        synchronizer.start()
        server.alertFeeds[1] = PhoneAlertFeed(listOf(highHeartRate("alert-1", 151)), 2, hasMore = false)

        events.publishMinuteUploadCompleted()

        assertEquals(1, notifier.shown.size)
        val details = repository.alerts.value.single().details as PatientAlert.Details.HighHeartRate
        assertEquals(151, details.maxHeartRate)
    }

    @Test
    fun `без разрешения оповещение остаётся ожидающим и показывается после разрешения`() {
        val server = FakeClinicServer().apply {
            alertFeeds[0] = PhoneAlertFeed(listOf(dailyTarget("alert-1")), 1, hasMore = false)
            alertFeeds[1] = PhoneAlertFeed(emptyList(), 1, hasMore = false)
        }
        val notifier = FakePatientAlertNotifier(canShow = false)
        val synchronizer = synchronizer(server, FakePatientAlertRepository(), notifier)
        synchronizer.start()
        notifier.canShow = true

        synchronizer.refreshNow()

        assertEquals(listOf("alert-1"), notifier.shown.map(PatientAlert::id))
    }

    @Test
    fun `сбой сети сохраняет локальный список и сообщает проблему`() {
        val server = FakeClinicServer().apply {
            alertFeeds[0] = PhoneAlertFeed(listOf(dailyTarget("alert-1")), 1, hasMore = false)
        }
        val repository = FakePatientAlertRepository()
        val synchronizer = synchronizer(server, repository, FakePatientAlertNotifier())
        synchronizer.start()
        server.failure = "нет сети"

        synchronizer.refreshNow()

        assertEquals("alert-1", repository.alerts.value.single().id)
        assertEquals("нет сети", synchronizer.state.value.problem)
        assertNull(synchronizer.state.value.takeIf { it.isSyncing })
    }

    private fun synchronizer(
        server: FakeClinicServer,
        repository: FakePatientAlertRepository,
        notifier: FakePatientAlertNotifier,
        events: InMemoryClinicSyncEvents = InMemoryClinicSyncEvents(),
    ): PatientAlertSynchronizer = PatientAlertSynchronizer(
        server,
        FakeServerRegistrationRepository(FakeClinicServer.REGISTRATION),
        repository,
        notifier,
        events,
        scope,
    )

    private fun trainingMinutesExceeded(): PhoneAlertReport = PhoneAlertReport(
        "alert-training",
        PhoneAlertReport.Kind.TrainingMinutesExceeded,
        LocalDate.parse("2026-11-12"),
        Instant.parse("2026-11-12T09:00:00Z"),
        null,
        null,
        null,
        PhoneAlertReport.TrainingMinutesExceeded(61, 60),
    )

    private fun dailyTarget(id: String): PhoneAlertReport = PhoneAlertReport(
        id,
        PhoneAlertReport.Kind.DailyTargetMissed,
        LocalDate.parse("2026-10-12"),
        Instant.parse("2026-10-12T17:00:00Z"),
        null,
        PhoneAlertReport.DailyTarget(12, 30, 6_200, 10_000),
        null,
    )

    private fun lowActivity(id: String): PhoneAlertReport = PhoneAlertReport(
        id,
        PhoneAlertReport.Kind.LowActivity,
        LocalDate.parse("2026-10-12"),
        Instant.parse("2026-10-12T17:00:00Z"),
        null,
        null,
        PhoneAlertReport.LowActivity(400, 1_000),
    )

    private fun highHeartRate(id: String, maximum: Int): PhoneAlertReport = PhoneAlertReport(
        id,
        PhoneAlertReport.Kind.HighHeartRate,
        null,
        Instant.parse("2026-10-12T07:20:00Z"),
        PhoneAlertReport.HighHeartRate(
            Instant.parse("2026-10-12T07:01:00Z"),
            Instant.parse("2026-10-12T07:06:00Z"),
            6,
            maximum,
            130,
        ),
        null,
        null,
    )
}

private class FakePatientAlertNotifier(var canShow: Boolean = true) : PatientAlertNotifier {
    val shown = mutableListOf<PatientAlert>()

    override fun show(alert: PatientAlert): Boolean {
        if (!canShow) return false
        shown += alert
        return true
    }
}
