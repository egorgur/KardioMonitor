package ru.kardiomonitor.bandprotocol.xiaomi

/** Источник задаётся явно на весь read/repeat/retention; fallback между индексами нет. */
internal enum class XiaomiHistoryIndex(val subtype: Int) {
    PAST(2), TODAY(1);

    val field: Int get() = 2
    fun request(): ByteArray {
        if (this == PAST) return XiaomiProto.command(8, subtype)
        val options = XiaomiProto.Writer().use { it.number(1, 0).build() }
        val health = try { XiaomiProto.Writer().use { it.bytes(5, options).build() } }
            finally { options.fill(0) }
        try { return XiaomiProto.Writer().use { it.number(1, 8).number(2, subtype.toLong()).bytes(10, health).build() } }
        finally { health.fill(0) }
    }
}
