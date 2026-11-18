package ru.kardiomonitor.features.monitoring.liveheartrate

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.kardiomonitor.features.clinic.domain.ClinicServer
import ru.kardiomonitor.features.clinic.domain.LiveHeartRateRequestStatusReport
import ru.kardiomonitor.features.clinic.domain.PhoneLiveHeartRateRequest
import ru.kardiomonitor.features.clinic.domain.ServerRegistration
import ru.kardiomonitor.features.clinic.domain.ServerRegistrationRepository
import ru.kardiomonitor.features.monitoring.domain.HistorySyncEvents
import ru.kardiomonitor.features.monitoring.domain.LiveHeartRateRequest
import ru.kardiomonitor.features.monitoring.domain.LiveHeartRateRequestRepository
import java.io.IOException
import java.time.Clock

/** После каждой попытки синхронизации получает запрос, сохраняет его и запускает Worker один раз. */
class LiveHeartRateRequestSynchronizer(
    private val server: ClinicServer,
    private val registrations: ServerRegistrationRepository,
    private val requests: LiveHeartRateRequestRepository,
    private val historyEvents: HistorySyncEvents,
    private val scope: CoroutineScope,
    private val schedule: (String) -> Unit,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val mutex = Mutex()

    fun start(): Job = scope.launch {
            registrations.registration.collectLatest { registration ->
                requests.selectPatient(registration?.patientId)
                if (registration != null) observeSyncs(registration)
            }
    }

    suspend fun report(requestId: String, report: LiveHeartRateRequest.Report) {
        mutex.withLock {
            val registration = registrations.registration.value ?: return
            val request = requests.request.value?.takeIf { it.id == requestId } ?: return
            requests.save(registration.patientId, request.withReport(report, clock.instant()))
            flush(registration)
        }
    }

    fun stopByPatient(requestId: String, onRecorded: () -> Unit) {
        scope.launch {
            val registration = registrations.registration.value
            if (registration != null) mutex.withLock {
                val request = requests.request.value?.takeIf { it.id == requestId }
                if (request != null) requests.save(
                    registration.patientId,
                    request.withReport(
                        LiveHeartRateRequest.Report(LiveHeartRateRequest.Status.StoppedByPatient),
                        clock.instant(),
                    ),
                )
            }
            onRecorded()
            if (registration != null) mutex.withLock { flush(registration) }
        }
    }

    private suspend fun observeSyncs(registration: ServerRegistration) {
        historyEvents.attemptsCompleted.collect { synchronize(registration) }
    }

    private suspend fun synchronize(registration: ServerRegistration) = mutex.withLock {
        flush(registration)
        val remote = try {
            server.activeLiveHeartRateRequest(registration)
        } catch (e: IOException) {
            Log.w(TAG, "не удалось получить запрос пульса", e)
            return@withLock
        } ?: return@withLock
        val current = requests.request.value
        val accepted = if (current?.id == remote.requestId) current else remote.toLocal()
        requests.save(registration.patientId, accepted)
        schedule(accepted.id)
    }

    private suspend fun flush(registration: ServerRegistration) {
        while (true) {
            val request = requests.request.value ?: return
            val report = request.reports.firstOrNull() ?: return
            try {
                server.reportLiveHeartRateRequestStatus(registration, request.id, report.toWire())
            } catch (e: IOException) {
                Log.w(TAG, "не удалось отправить состояние запроса пульса", e)
                return
            }
            requests.save(registration.patientId, request.acknowledgeFirstReport())
        }
    }

    private fun PhoneLiveHeartRateRequest.toLocal(): LiveHeartRateRequest = LiveHeartRateRequest(
        id = requestId,
        durationMinutes = durationMinutes,
        requestedAt = requestedAt,
        expiresAt = expiresAt,
        status = status.toLocal(),
        startedAt = startedAt,
    )

    private fun LiveHeartRateRequest.Report.toWire(): LiveHeartRateRequestStatusReport =
        LiveHeartRateRequestStatusReport(status.toWire(), failureReason?.toWire())

    private companion object {
        const val TAG = "KardioServer"
    }
}

private fun PhoneLiveHeartRateRequest.Status.toLocal(): LiveHeartRateRequest.Status = when (this) {
    PhoneLiveHeartRateRequest.Status.WaitingForPhone -> LiveHeartRateRequest.Status.WaitingForPhone
    PhoneLiveHeartRateRequest.Status.Running -> LiveHeartRateRequest.Status.Running
    PhoneLiveHeartRateRequest.Status.Completed -> LiveHeartRateRequest.Status.Completed
    PhoneLiveHeartRateRequest.Status.StoppedByPatient -> LiveHeartRateRequest.Status.StoppedByPatient
    PhoneLiveHeartRateRequest.Status.Failed -> LiveHeartRateRequest.Status.Failed
}

private fun LiveHeartRateRequest.Status.toWire(): PhoneLiveHeartRateRequest.Status = when (this) {
    LiveHeartRateRequest.Status.WaitingForPhone -> PhoneLiveHeartRateRequest.Status.WaitingForPhone
    LiveHeartRateRequest.Status.Running -> PhoneLiveHeartRateRequest.Status.Running
    LiveHeartRateRequest.Status.Completed -> PhoneLiveHeartRateRequest.Status.Completed
    LiveHeartRateRequest.Status.StoppedByPatient -> PhoneLiveHeartRateRequest.Status.StoppedByPatient
    LiveHeartRateRequest.Status.Failed -> PhoneLiveHeartRateRequest.Status.Failed
}

private fun LiveHeartRateRequest.FailureReason.toWire(): PhoneLiveHeartRateRequest.FailureReason = when (this) {
    LiveHeartRateRequest.FailureReason.Expired -> PhoneLiveHeartRateRequest.FailureReason.Expired
    LiveHeartRateRequest.FailureReason.BandUnavailable -> PhoneLiveHeartRateRequest.FailureReason.BandUnavailable
    LiveHeartRateRequest.FailureReason.NotificationPermissionDenied ->
        PhoneLiveHeartRateRequest.FailureReason.NotificationPermissionDenied
    LiveHeartRateRequest.FailureReason.CompanionAssociationMissing ->
        PhoneLiveHeartRateRequest.FailureReason.CompanionAssociationMissing
    LiveHeartRateRequest.FailureReason.MeasurementFailed -> PhoneLiveHeartRateRequest.FailureReason.MeasurementFailed
}
