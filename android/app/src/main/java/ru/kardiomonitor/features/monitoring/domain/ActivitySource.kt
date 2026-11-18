package ru.kardiomonitor.features.monitoring.domain

import kotlinx.coroutines.flow.Flow
import java.time.ZonedDateTime

/**
 * Источник данных активности — браслет на языке контекста «Мониторинг». Реализует ACL
 * `platform.huami.HuamiBand`. Работает только при открытом сеансе связи
 * (`features.band.api.BandConnection`); сбои связи — `java.io.IOException`.
 */
interface ActivitySource {
    /** Непроверенный протокол не открывает сеанс и не выдаёт пустую историю за успешную выгрузку. */
    val monitoringUnavailableReason: String? get() = null

    /** Минуты активности начиная с [start]; [onProgress] — сколько минут уже получено. */
    suspend fun minutesSince(start: ZonedDateTime, onProgress: (minutesReceived: Int) -> Unit): List<ActivityMinute>

    /** Пульс сейчас — непрерывное измерение, пока поток собирают. null — замер ещё идёт. */
    fun liveHeartRate(): Flow<HeartRate?>

    /** Шаги за сегодня: текущее значение, затем обновления от браслета. */
    fun stepsToday(): Flow<StepCount>
}
