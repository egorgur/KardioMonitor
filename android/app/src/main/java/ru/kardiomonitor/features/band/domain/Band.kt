package ru.kardiomonitor.features.band.domain

/**
 * Браслет пациента — корень агрегата: учётные данные и заданные приложением настройки.
 * Изменения — только через методы агрегата; новая привязка ([paired]) сбрасывает настройки.
 *
 * @property heartRateMeasurementInterval null — из приложения не задавалась, действует настройка Mi Fitness.
 */
@ConsistentCopyVisibility
data class Band private constructor(
    val credentials: BandCredentials,
    val heartRateMeasurementInterval: HeartRateMeasurementInterval?,
) {
    fun withHeartRateMeasurementInterval(interval: HeartRateMeasurementInterval): Band =
        Band(credentials, interval)

    companion object {
        /** Привязка браслета: настройки прежнего браслета к новому не переносятся. */
        fun paired(credentials: BandCredentials): Band = Band(credentials, heartRateMeasurementInterval = null)

        /** Восстановление сохранённого браслета хранилищем. */
        fun restore(credentials: BandCredentials, heartRateMeasurementInterval: HeartRateMeasurementInterval?): Band =
            Band(credentials, heartRateMeasurementInterval)
    }
}
