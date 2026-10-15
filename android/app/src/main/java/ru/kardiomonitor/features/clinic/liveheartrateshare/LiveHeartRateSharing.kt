package ru.kardiomonitor.features.clinic.liveheartrateshare

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import ru.kardiomonitor.features.clinic.domain.ClinicServer
import ru.kardiomonitor.features.clinic.domain.LiveHeartRateReport
import ru.kardiomonitor.features.clinic.domain.ServerRegistrationRepository
import ru.kardiomonitor.features.monitoring.domain.LiveHeartRateFeed
import java.io.IOException

/**
 * Точка входа слайса для `app`: каждое значение «пульса сейчас» (событие [LiveHeartRateFeed])
 * уходит на сервер, пока телефон зарегистрирован. Измерение запускает пользователь в секции
 * «Пульс сейчас» — этот слайс второго измерения не начинает. Медленная сеть — старые значения
 * пропускаются (`conflate`): врачу важен свежий пульс. Сбой — в журнал, следующее значение
 * уйдёт через 2–3 с.
 */
class LiveHeartRateSharing(
    private val server: ClinicServer,
    private val registrations: ServerRegistrationRepository,
    private val feed: LiveHeartRateFeed,
    private val scope: CoroutineScope,
) {
    fun start() {
        scope.launch {
            feed.readings.conflate().collect { reading ->
                val registration = registrations.registration.value ?: return@collect
                try {
                    server.sendLiveHeartRate(registration, LiveHeartRateReport(reading.measuredAt, reading.heartRate.bpm))
                } catch (e: IOException) {
                    Log.w(TAG, "пульс сейчас не отправлен: ${e.message}")
                }
            }
        }
    }

    private companion object {
        const val TAG = "KardioServer"
    }
}
