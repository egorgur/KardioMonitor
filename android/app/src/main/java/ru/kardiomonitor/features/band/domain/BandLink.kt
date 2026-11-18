package ru.kardiomonitor.features.band.domain

/**
 * Канал связи с браслетом на языке контекста «Браслет». Реализует ACL `platform.huami.HuamiBand`.
 * Пользуется им только сеанс связи (`features.band.api.BandConnection`): он следит, чтобы
 * соединение было одно (ADR-0003). Сбои связи в операциях — `java.io.IOException`.
 */
interface BandLink {
    /** Вызывается при потере соединения (не при [close]). */
    var onConnectionLost: (() -> Unit)?

    /** Ищет браслет, подключается и проходит авторизацию; стадии сообщает в [onStage]. */
    suspend fun open(credentials: BandCredentials, onStage: (ConnectionStage) -> Unit): OpenResult

    fun close()

    suspend fun readBattery(): BatteryLevel?

    suspend fun readHeartRateSettings(): ReportedHeartRateSettings? = null

    suspend fun writeHeartRateMeasurementInterval(interval: HeartRateMeasurementInterval)
}
