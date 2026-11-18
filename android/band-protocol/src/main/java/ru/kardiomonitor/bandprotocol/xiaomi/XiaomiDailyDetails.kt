package ru.kardiomonitor.bandprotocol.xiaomi

/** Проверенный в probe 0.31 layout v4; HR0/255 не объявляются missing или измерениями. */
internal object XiaomiDailyDetails {
    fun parse(bytes: ByteArray, size: Int): XiaomiHistoryAggregate {
        var heartRatePresent = 0
        var heartRateZero = 0
        var heartRate255 = 0
        var stepPresent = 0
        var stepZero = 0
        var stepNonzero = 0
        val records = forEachRecord(bytes, size) { _, steps, heartRate ->
            heartRate?.let {
                heartRatePresent++
                if (it == 0) heartRateZero++
                if (it == 255) heartRate255++
            }
            steps?.let {
                stepPresent++
                if (it == 0) stepZero++ else stepNonzero++
            }
        }
        return XiaomiHistoryAggregate(
            records = records,
            heartRatePresentFields = heartRatePresent,
            heartRateZeroCodes = heartRateZero,
            heartRate255Codes = heartRate255,
            stepPresentFields = stepPresent,
            stepZeroCodes = stepZero,
            stepNonzeroCodes = stepNonzero,
        )
    }

    /**
     * Wire-поля v4 после внешней проверки CRC и exact ID, не клинические минуты.
     * minuteIndex — позиция в файле, а не подтверждённое время. Незавершённый файл
     * не возвращает частичный список. Обычный aggregate-путь не сохраняет записи.
     */
    fun parseRecords(bytes: ByteArray, size: Int): List<XiaomiDailyDetailRecord> = buildList {
        forEachRecord(bytes, size) { index, steps, heartRate ->
            add(XiaomiDailyDetailRecord(index, steps, heartRate))
        }
    }

    private inline fun forEachRecord(bytes: ByteArray, size: Int, accept: (Int, Int?, Int?) -> Unit): Int {
        if (size !in 18..bytes.size || bytes[5] != 4.toByte() || bytes[6] != 0.toByte() || bytes[7] != 0.toByte())
            XiaomiProto.invalid()
        val end = size - 4
        var position = 14
        var recordIndex = 0
        while (position < end) {
            val start = position
            var steps: Int? = null
            var heartRate: Int? = null
            var extra = false
            for (group in WIDTHS.indices) {
                val header = XiaomiWire.u(bytes[8 + group / 2])
                val nibble = if (group % 2 == 0) header ushr 4 else header and 15
                if (nibble and 8 != 0) {
                    if (end - position < WIDTHS[group]) XiaomiProto.invalid()
                    if (group == 0) {
                        val packed = XiaomiWire.le16(bytes, position)
                        if (nibble and 1 != 0) steps = packed and 0x3fff
                        extra = nibble and 2 != 0 && packed and 0x4000 != 0
                    }
                    if (group == 4 && nibble and 4 != 0) heartRate = XiaomiWire.u(bytes[position])
                    position += WIDTHS[group]
                }
                if (group == 8 && extra) {
                    if (position == end) XiaomiProto.invalid()
                    position++
                }
            }
            if (position == start || ++recordIndex > 2880) XiaomiProto.invalid()
            accept(recordIndex - 1, steps, heartRate)
        }
        return recordIndex
    }

    private val WIDTHS = intArrayOf(2, 1, 1, 2, 1, 1, 2, 1, 1, 2, 2)
}
