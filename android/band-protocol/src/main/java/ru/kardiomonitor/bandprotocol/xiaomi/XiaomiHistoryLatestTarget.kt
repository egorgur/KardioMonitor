package ru.kardiomonitor.bandprotocol.xiaomi

/** Явный latest-supported выбор одного ID; не зависит от HR и не перебирает файлы. */
internal class XiaomiHistoryLatestTarget(private val clockEpochSeconds: Long, private val utcOffsetSeconds: Int) {
    init {
        if (clockEpochSeconds !in 0..Int.MAX_VALUE.toLong() || utcOffsetSeconds !in -43_200..50_400 ||
            utcOffsetSeconds % 900 != 0) XiaomiProto.invalid()
    }
    fun select(details: List<ByteArray>): Int {
        if (details.any { it.size != 7 || it[5] != 4.toByte() || it[6] != 0.toByte() }) XiaomiProto.invalid()
        val candidates = details.indices.filter {
            val start = start(details[it])
            start <= Int.MAX_VALUE && start % 60 == 0L && start <= clockEpochSeconds &&
                details[it][4].toInt() * 900 == utcOffsetSeconds
        }
        val newest = candidates.maxOfOrNull { start(details[it]) } ?: XiaomiProto.invalid()
        return candidates.filter { start(details[it]) == newest }.singleOrNull() ?: XiaomiProto.invalid()
    }
    private fun start(id: ByteArray): Long = (0..3).fold(0L) { value, byte ->
        value or (XiaomiWire.u(id[byte]).toLong() shl (8 * byte))
    }
    override fun toString(): String = "XiaomiHistoryLatestTarget"
}
