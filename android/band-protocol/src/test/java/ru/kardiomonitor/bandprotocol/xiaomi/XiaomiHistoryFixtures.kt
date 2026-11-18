package ru.kardiomonitor.bandprotocol.xiaomi

import java.util.zip.CRC32

internal object XiaomiHistoryFixtures {
    val id: ByteArray get() = byteArrayOf(1, 2, 3, 4, -4, 4, 0)
    fun file(identifier: ByteArray = id, body: ByteArray = byteArrayOf(
        0xb0.toByte(), 0, 0xc0.toByte(), 0, 0, 0, 1, 0, 70, 2, 0x40, 0, 99,
    )): ByteArray = ByteArray(12 + body.size).also {
        identifier.copyInto(it); body.copyInto(it, 8); crc(it)
    }
    fun crc(bytes: ByteArray) {
        val crc = CRC32().apply { update(bytes, 0, bytes.size - 4) }.value
        repeat(4) { bytes[bytes.size - 4 + it] = (crc ushr (8 * it)).toByte() }
    }
    fun chunk(file: ByteArray, total: Int = 1, number: Int = 1, start: Int = 0, end: Int = file.size): ByteArray =
        byteArrayOf(total.toByte(), (total ushr 8).toByte(), number.toByte(), (number ushr 8).toByte()) + file.copyOfRange(start, end)
    fun index(ids: ByteArray, source: XiaomiHistoryIndex = XiaomiHistoryIndex.PAST): XiaomiProto {
        val health = XiaomiProto.Writer().use { it.bytes(source.field, ids).build() }
        val command = XiaomiProto.Writer().use { it.number(1, 8).number(2, source.subtype.toLong()).bytes(10, health).build() }
        try { return XiaomiProto.parse(command) } finally { command.fill(0); health.fill(0) }
    }
}
