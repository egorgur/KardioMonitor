package ru.kardiomonitor.features.band.api

import ru.kardiomonitor.features.band.domain.BatteryLevel
import ru.kardiomonitor.features.band.domain.ConnectionProblem
import ru.kardiomonitor.features.band.domain.ConnectionStage
import ru.kardiomonitor.features.band.domain.HeartRateMeasurementInterval
import ru.kardiomonitor.features.band.domain.ReportedHeartRateSettings

/**
 * Состояние сеанса связи.
 *
 * @property isBackground соединение открыла фоновая синхронизация, а не кнопка «Подключить».
 * @property battery заряд при последнем подключении.
 * @property appliedInterval частота измерения пульса, записанная в браслет с запуска
 *   приложения; null — ещё не записывалась.
 * @property problem почему сеанса нет или он работает не полностью; null — всё в порядке.
 */
data class ConnectionState(
    val stage: ConnectionStage = ConnectionStage.Disconnected,
    val isBackground: Boolean = false,
    val battery: BatteryLevel? = null,
    val appliedInterval: HeartRateMeasurementInterval? = null,
    val problem: ConnectionProblem? = null,
    val reportedHeartRateSettings: ReportedHeartRateSettings? = null,
) {
    val isConnected: Boolean get() = stage == ConnectionStage.Connected
}
