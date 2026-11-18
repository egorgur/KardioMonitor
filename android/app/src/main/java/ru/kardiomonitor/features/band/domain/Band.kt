package ru.kardiomonitor.features.band.domain

import java.time.Instant

/**
 * Браслет пациента — корень агрегата: учётные данные, момент привязки и заданные приложением
 * настройки. Изменения — только через методы агрегата; новая привязка ([paired]) сбрасывает настройки.
 *
 * @property pairedAt когда пациент привязал браслет (отсканировал QR); раньше этого момента
 *   история браслета чужая (ADR-0011). null — браслет привязан до того, как приложение стало его запоминать.
 * @property heartRateMeasurementInterval null — из приложения не задавалась, действует настройка Mi Fitness.
 */
@ConsistentCopyVisibility
data class Band private constructor(
    val credentials: BandCredentials,
    val pairedAt: Instant?,
    val heartRateMeasurementInterval: HeartRateMeasurementInterval?,
) {
    fun withHeartRateMeasurementInterval(interval: HeartRateMeasurementInterval): Band {
        require(credentials.protocol.canWriteMeasurementInterval) { "запись настроек этого протокола не проверена" }
        return Band(credentials, pairedAt, interval)
    }

    companion object {
        /** Привязка браслета в момент [at]: настройки прежнего браслета к новому не переносятся. */
        fun paired(credentials: BandCredentials, at: Instant): Band =
            Band(credentials, pairedAt = at, heartRateMeasurementInterval = null)

        /** Восстановление сохранённого браслета хранилищем. */
        fun restore(
            credentials: BandCredentials,
            pairedAt: Instant?,
            heartRateMeasurementInterval: HeartRateMeasurementInterval?,
        ): Band = Band(credentials, pairedAt, heartRateMeasurementInterval)
    }
}
