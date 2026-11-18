package ru.kardiomonitor.bandprotocol.xiaomi

/** Только после внешних exact-ID/CRC/padding guards; сравнивается весь валидный layout. */
internal object XiaomiHistoryReferenceCheck {
    fun compare(bytes: ByteArray, size: Int, reference: XiaomiHistoryReference): XiaomiHistoryReferenceComparison {
        val records = XiaomiDailyDetails.parseRecords(bytes, size)
        val start = (0..3).fold(0L) { value, index -> value or (XiaomiWire.u(bytes[index]).toLong() shl (8 * index)) }
        val delta = reference.epochSeconds - start
        // До независимой проверки signed/u32 расхождения после 2038 не выбираем трактовку.
        val supported = start <= Int.MAX_VALUE && start % 60 == 0L && delta >= 0 && delta % 60 == 0L
        val record = if (supported && delta / 60 < records.size) records[(delta / 60).toInt()] else null
        return XiaomiHistoryReferenceComparison(
            recordLocated = record != null,
            timezoneMatches = bytes[4].toInt() * 900 == reference.utcOffsetSeconds,
            heartRateMatches = record?.let { found -> reference.heartRate?.let { it == found.heartRateRaw } },
            stepsMatch = record?.let { found -> reference.steps?.let { it == found.steps } },
        )
    }
}
