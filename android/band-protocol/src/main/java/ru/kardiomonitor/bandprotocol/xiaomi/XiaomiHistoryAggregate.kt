package ru.kardiomonitor.bandprotocol.xiaomi

/** Категории кодирования, не измерения и не нормализованные клинические минуты. */
data class XiaomiHistoryAggregate internal constructor(
    val records: Int,
    val heartRatePresentFields: Int,
    val heartRateZeroCodes: Int,
    val heartRate255Codes: Int,
    val stepPresentFields: Int,
    val stepZeroCodes: Int,
    val stepNonzeroCodes: Int,
) {
    override fun toString(): String = "XiaomiHistoryAggregate"
}
