package ru.kardiomonitor.bandprotocol.xiaomi

/** Независимая точка из Mi Fitness, не из проверяемого файла или его агрегатов. */
internal data class XiaomiHistoryReference(
    val epochSeconds: Long,
    val utcOffsetSeconds: Int,
    val heartRate: Int?,
    val steps: Int?,
) {
    init {
        require(epochSeconds in 0..Int.MAX_VALUE.toLong() && epochSeconds % 60 == 0L) { "Некорректное время эталона" }
        require(utcOffsetSeconds in -43_200..50_400 && utcOffsetSeconds % 900 == 0) { "Некорректная зона эталона" }
        require(heartRate == null || heartRate in 1..254) { "Некорректный пульс эталона" }
        require(steps == null || steps in 0..16_383) { "Некорректные шаги эталона" }
        require(heartRate != null || steps != null) { "Нет показателей эталона" }
    }
    override fun toString(): String = "XiaomiHistoryReference"
}
