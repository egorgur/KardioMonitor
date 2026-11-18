package ru.kardiomonitor.bandprotocol.xiaomi

internal class XiaomiHistorySnapshot(command: XiaomiProto, private val index: XiaomiHistoryIndex = XiaomiHistoryIndex.PAST) : AutoCloseable {
    private val identifiers: ByteArray
    private var closed = false
    init {
        if (command.number(1) != 8L || command.number(2) != index.subtype.toLong()) XiaomiProto.invalid()
        command.success()
        identifiers = command.message(10).use { health ->
            val bytes = health.bytes(index.field)
            if (bytes.size > 256 * 7 || bytes.size % 7 != 0) XiaomiProto.invalid()
            bytes.copyOf()
        }
    }
    fun details(): List<ByteArray> {
        if (closed) XiaomiProto.invalid()
        val selected = mutableListOf<ByteArray>()
        for (offset in identifiers.indices step 7) {
            if (identifiers[offset + 5] != 4.toByte() || identifiers[offset + 6] != 0.toByte()) continue
            if (selected.none { matches(it, identifiers, offset) })
                selected.add(identifiers.copyOfRange(offset, offset + 7))
        }
        return selected
    }
    fun unchanged(other: XiaomiHistorySnapshot): Boolean {
        if (closed || other.closed) XiaomiProto.invalid()
        if (index != other.index || identifiers.size != other.identifiers.size) return false
        val matched = BooleanArray(identifiers.size / 7)
        for (offset in identifiers.indices step 7) {
            val found = matched.indices.firstOrNull { index ->
                !matched[index] && (0..6).all { identifiers[offset + it] == other.identifiers[index * 7 + it] }
            } ?: return false
            matched[found] = true
        }
        return true
    }
    override fun close() { closed = true; identifiers.fill(0) }
    override fun toString(): String = "XiaomiHistorySnapshot"
    companion object {
        fun request(identifier: ByteArray): ByteArray {
            if (identifier.size != 7 || identifier[5] != 4.toByte() || identifier[6] != 0.toByte()) XiaomiProto.invalid()
            val health = XiaomiProto.Writer().use { it.bytes(2, identifier).build() }
            try { return XiaomiProto.Writer().use { it.number(1, 8).number(2, 3).bytes(10, health).build() } }
            finally { health.fill(0) }
        }
        fun matches(identifier: ByteArray, bytes: ByteArray, offset: Int): Boolean =
            identifier.size == 7 && (0..6).all { identifier[it] == bytes[offset + it] }
    }
}
