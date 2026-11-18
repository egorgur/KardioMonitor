package ru.kardiomonitor.platform.huami

import java.time.Instant

/** Сырая минутная запись истории браслета, независимая от домена приложения. */
data class ActivityRecord(
    val recordedAt: Instant,
    val kind: Int,
    val intensity: Int,
    val steps: Int,
    val heartRate: Int,
    val extra: Int,
    val sleep: Int,
    val deepSleep: Int,
    val remSleep: Int,
)

internal fun parseActivityRecords(start: Instant, data: ByteArray): List<ActivityRecord> {
    val recordSize = 8
    return (0 until data.size / recordSize).map { index ->
        val offset = index * recordSize
        ActivityRecord(
            recordedAt = start.plusSeconds(index * 60L),
            kind = data[offset].toInt() and 0xFF,
            intensity = data[offset + 1].toInt() and 0xFF,
            steps = data[offset + 2].toInt() and 0xFF,
            heartRate = data[offset + 3].toInt() and 0xFF,
            extra = data[offset + 4].toInt() and 0xFF,
            sleep = data[offset + 5].toInt() and 0xFF,
            deepSleep = data[offset + 6].toInt() and 0xFF,
            remSleep = data[offset + 7].toInt() and 0xFF,
        )
    }
}
