package ru.kardiomonitor.features.alerts.alertsync

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.kardiomonitor.features.alerts.api.PatientAlertSync
import ru.kardiomonitor.features.alerts.domain.PatientAlert
import ru.kardiomonitor.features.alerts.domain.PatientAlertNotifier
import ru.kardiomonitor.features.alerts.domain.PatientAlertRepository
import ru.kardiomonitor.features.alerts.domain.PatientAlertSyncStatus
import ru.kardiomonitor.features.clinic.domain.ClinicServer
import ru.kardiomonitor.features.clinic.domain.ClinicSyncEvents
import ru.kardiomonitor.features.clinic.domain.PhoneAlertReport
import ru.kardiomonitor.features.clinic.domain.ServerRegistration
import ru.kardiomonitor.features.clinic.domain.ServerRegistrationRepository
import java.io.IOException

/** Забирает ленту после регистрации и успешной отправки минут; обновления не уведомляет повторно. */
class PatientAlertSynchronizer(
    private val server: ClinicServer,
    private val registrations: ServerRegistrationRepository,
    private val repository: PatientAlertRepository,
    private val notifier: PatientAlertNotifier,
    private val syncEvents: ClinicSyncEvents,
    private val scope: CoroutineScope,
) : PatientAlertSync {
    private val mutex = Mutex()
    private val current = MutableStateFlow(PatientAlertSyncStatus())

    override val state: StateFlow<PatientAlertSyncStatus> = current.asStateFlow()

    override fun start() {
        scope.launch {
            combine(
                registrations.registration,
                syncEvents.minuteUploadCompleted.onStart { emit(Unit) },
            ) { registration, _ -> registration }
                .conflate()
                .collect { registration -> selectAndRefresh(registration) }
        }
    }

    override fun refreshNow() {
        val registration = registrations.registration.value ?: return
        scope.launch { refresh(registration) }
    }

    private suspend fun selectAndRefresh(registration: ServerRegistration?) {
        repository.selectPatient(registration?.patientId)
        current.update { it.copy(isRegistered = registration != null) }
        if (registration != null) refresh(registration)
    }

    private suspend fun refresh(registration: ServerRegistration) = mutex.withLock {
        current.update { it.copy(isSyncing = true) }
        try {
            fetchAll(registration)
            notifyPending(registration.patientId)
            current.value = PatientAlertSyncStatus(isRegistered = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "не удалось получить оповещения клиники", e)
            current.value = PatientAlertSyncStatus(isRegistered = true, problem = e.message)
        }
    }

    private suspend fun fetchAll(registration: ServerRegistration) {
        var cursor = repository.cursor(registration.patientId)
        do {
            val page = server.patientAlerts(registration, cursor)
            if (page.hasMore && page.cursor <= cursor) throw IOException("сервер не продвинул курсор оповещений")
            repository.save(registration.patientId, page.alerts.map { it.toPatientAlert() }, page.cursor)
            cursor = page.cursor
        } while (page.hasMore)
    }

    private fun notifyPending(patientId: String) {
        repository.alerts.value
            .filterNot { repository.wasNotified(patientId, it.id) }
            .forEach { alert -> if (notifier.show(alert)) repository.markNotified(patientId, alert.id) }
    }

    private companion object {
        const val TAG = "KardioServer"
    }
}

private fun PhoneAlertReport.toPatientAlert(): PatientAlert = PatientAlert(
    id = alertId,
    kind = when (kind) {
        PhoneAlertReport.Kind.HighHeartRate -> PatientAlert.Kind.HighHeartRate
        PhoneAlertReport.Kind.DailyTargetMissed -> PatientAlert.Kind.DailyTargetMissed
        PhoneAlertReport.Kind.DailyTargetMet -> PatientAlert.Kind.DailyTargetMet
        PhoneAlertReport.Kind.LowActivity -> PatientAlert.Kind.LowActivity
        PhoneAlertReport.Kind.TrainingMinutesExceeded -> PatientAlert.Kind.TrainingMinutesExceeded
    },
    day = day,
    createdAt = createdAt,
    details = when (kind) {
        PhoneAlertReport.Kind.HighHeartRate -> requireNotNull(highHeartRate).toDetails()
        PhoneAlertReport.Kind.DailyTargetMissed, PhoneAlertReport.Kind.DailyTargetMet -> requireNotNull(dailyTarget).toDetails()
        PhoneAlertReport.Kind.LowActivity -> requireNotNull(lowActivity).toDetails()
        PhoneAlertReport.Kind.TrainingMinutesExceeded -> requireNotNull(trainingMinutesExceeded).toDetails()
    },
)

private fun PhoneAlertReport.HighHeartRate.toDetails(): PatientAlert.Details.HighHeartRate =
    PatientAlert.Details.HighHeartRate(startedAt, lastAt, durationMinutes, maxHeartRate, thresholdBpm)

private fun PhoneAlertReport.DailyTarget.toDetails(): PatientAlert.Details.DailyTarget =
    PatientAlert.Details.DailyTarget(trainingMinutes, targetTrainingMinutes, steps, targetSteps)

private fun PhoneAlertReport.LowActivity.toDetails(): PatientAlert.Details.LowActivity =
    PatientAlert.Details.LowActivity(steps, thresholdSteps)

private fun PhoneAlertReport.TrainingMinutesExceeded.toDetails(): PatientAlert.Details.TrainingMinutesExceeded =
    PatientAlert.Details.TrainingMinutesExceeded(trainingMinutes, thresholdMinutes)
