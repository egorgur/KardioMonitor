package ru.kardiomonitor.bandprotocol.xiaomi

import java.io.File
import java.io.IOException
import org.json.JSONObject

/** Эталон передаётся через stdin runner в private storage, никогда через Intent/log. */
internal object XiaomiHardwareReference {
    fun consume(file: File): XiaomiHistoryReference {
        val bytes = ByteArray(1025)
        try {
            val count = file.inputStream().use { input ->
                var size = 0
                while (size < bytes.size) {
                    val next = input.read(bytes, size, bytes.size - size)
                    if (next < 0) break
                    if (next == 0) throw IOException("Некорректный эталон")
                    size += next
                }
                size
            }
            if (count !in 1..1024) throw IOException("Некорректный эталон")
            return parse(JSONObject(String(bytes, 0, count, Charsets.UTF_8)))
        } catch (_: Exception) {
            throw IOException("Некорректный эталон")
        } finally {
            bytes.fill(0)
            if (file.exists() && !file.delete()) throw IOException("Не удалось удалить эталон")
        }
    }
    private fun parse(json: JSONObject): XiaomiHistoryReference {
        val fields = setOf("epoch_seconds", "utc_offset_seconds", "heart_rate", "steps")
        if (json.keys().asSequence().toSet() != fields) throw IOException("Некорректный эталон")
        val epoch = integer(json, "epoch_seconds") ?: throw IOException("Некорректный эталон")
        val offset = integer(json, "utc_offset_seconds") ?: throw IOException("Некорректный эталон")
        if (offset !in -43_200..50_400) throw IOException("Некорректный эталон")
        val heartRate = integer(json, "heart_rate")
        val steps = integer(json, "steps")
        if (heartRate != null && heartRate !in 1..254 || steps != null && steps !in 0..16_383)
            throw IOException("Некорректный эталон")
        return XiaomiHistoryReference(epoch, offset.toInt(), heartRate?.toInt(), steps?.toInt())
    }
    private fun integer(json: JSONObject, name: String): Long? = when (val value = json.get(name)) {
        JSONObject.NULL -> null
        is Int -> value.toLong()
        is Long -> value
        else -> throw IOException("Некорректный эталон")
    }
}
