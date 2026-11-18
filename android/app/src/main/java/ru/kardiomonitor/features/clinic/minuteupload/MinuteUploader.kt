package ru.kardiomonitor.features.clinic.minuteupload

import android.util.Log
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
import ru.kardiomonitor.features.clinic.domain.ClinicServer
import ru.kardiomonitor.features.clinic.domain.ClinicSyncEvents
import ru.kardiomonitor.features.clinic.domain.ServerRegistration
import ru.kardiomonitor.features.clinic.domain.ServerRegistrationRepository
import ru.kardiomonitor.features.monitoring.domain.ActivityHistory
import java.io.IOException
import java.time.Clock
import java.time.Instant

/** Итог последней отправки минут врачу. */
internal data class MinuteUploadState(
    val isRegistered: Boolean = false,
    val isUploading: Boolean = false,
    val lastAttemptAt: Instant? = null,
    val lastSentMinutes: Int? = null,
    val problem: String? = null,
)

/**
 * Точка входа слайса для `app`: отправляет новые минуты врачу после каждого пополнения
 * истории (событие `ActivityHistory.changes` — синхронизация с экрана и из фона) и сразу
 * после регистрации на сервере. Отправки не пересекаются (mutex).
 */
class MinuteUploader(
    server: ClinicServer,
    private val registrations: ServerRegistrationRepository,
    private val history: ActivityHistory,
    private val scope: CoroutineScope,
    private val syncEvents: ClinicSyncEvents,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val uploadNewMinutes = UploadNewMinutes(server, history)
    private val mutex = Mutex()
    private val current = MutableStateFlow(MinuteUploadState())

    internal val state: StateFlow<MinuteUploadState> = current.asStateFlow()

    fun start() {
        scope.launch {
            combine(registrations.registration, history.changes.onStart { emit(Unit) }) { registration, _ -> registration }
                .conflate()
                .collect { registration ->
                    current.update { it.copy(isRegistered = registration != null) }
                    if (registration != null) upload(registration)
                }
        }
    }

    /** Кнопка «Отправить сейчас». */
    fun uploadNow() {
        val registration = registrations.registration.value ?: return
        scope.launch { upload(registration) }
    }

    private suspend fun upload(registration: ServerRegistration) = mutex.withLock {
        current.update { it.copy(isUploading = true) }
        val outcome = try {
            val sent = uploadNewMinutes(registration)
            syncEvents.publishMinuteUploadCompleted()
            Log.i(TAG, "отправлено врачу: $sent мин")
            current.value.copy(lastSentMinutes = sent, problem = null)
        } catch (e: IOException) {
            Log.w(TAG, "отправка минут не удалась", e)
            current.value.copy(problem = e.message ?: e.toString())
        }
        current.value = outcome.copy(isUploading = false, lastAttemptAt = clock.instant())
    }

    private companion object {
        const val TAG = "KardioServer"
    }
}
