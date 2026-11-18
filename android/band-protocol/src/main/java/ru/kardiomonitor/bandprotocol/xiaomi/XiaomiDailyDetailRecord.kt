package ru.kardiomonitor.bandprotocol.xiaomi

/**
 * Одноминутная запись details v4 в исходном кодировании Xiaomi.
 *
 * Null означает отсутствие поля в bitmap; нулевые и 255 для HR сохраняются как есть и
 * не объявляются автоматически отсутствующими измерениями.
 */
internal data class XiaomiDailyDetailRecord(
    val minuteIndex: Int,
    val steps: Int?,
    val heartRateRaw: Int?,
) {
    override fun toString(): String = "XiaomiDailyDetailRecord"
}
