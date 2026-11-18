package ru.kardiomonitor.bandprotocol.xiaomi

import java.io.InputStream

/** SPP v1 используется только для negotiation; рабочий транспорт — проверенный v2.
 * Источник: docs/band9-implementation-context.md §6.1 и оригинальный локальный Wire.java.
 */
internal object XiaomiWire {
    const val MAX_PAYLOAD: Int = 4096
    class Frame(val type: Int, val sequence: Int, val payload: ByteArray) : AutoCloseable {
        override fun close() { payload.fill(0) }
        override fun toString(): String = "XiaomiFrame"
    }
    fun versionRequest(): ByteArray = byteArrayOf(0xba.toByte(), 0xdc.toByte(), 0xfe.toByte(), 0, 0xc0.toByte(), 3, 0, 0, 0, 0, 0xef.toByte())
    fun version(input: InputStream): Int {
        val header = exact(input, 10)
        if (u(header[0]) != 0xba || u(header[1]) != 0xdc || u(header[2]) != 0xfe ||
            u(header[3]) != 0 || u(header[4]) and 0x3f != 0) XiaomiProto.invalid()
        val length = le16(header, 5) - 3
        if (length !in 1..64) XiaomiProto.invalid()
        val payload = exact(input, length)
        try {
            if (input.read() != 0xef) XiaomiProto.invalid()
            return u(payload[0])
        } finally { payload.fill(0) }
    }
    fun frame(type: Int, sequence: Int, payload: ByteArray): ByteArray {
        if (type !in 0..3 || sequence !in 0..255 || payload.size > MAX_PAYLOAD) XiaomiProto.invalid()
        val result = ByteArray(8 + payload.size)
        result[0] = 0xa5.toByte(); result[1] = 0xa5.toByte()
        result[2] = type.toByte(); result[3] = sequence.toByte()
        put16(result, 4, payload.size); put16(result, 6, crc16(payload))
        payload.copyInto(result, 8)
        return result
    }
    fun read(input: InputStream): Frame {
        val header = exact(input, 8)
        val type = u(header[2])
        val size = le16(header, 4)
        if (u(header[0]) != 0xa5 || u(header[1]) != 0xa5 || type !in 0..3 ||
            size > MAX_PAYLOAD || (type <= 1 && size != 0)) XiaomiProto.invalid()
        val payload = exact(input, size)
        if (crc16(payload) != le16(header, 6)) { payload.fill(0); XiaomiProto.invalid() }
        return Frame(type, u(header[3]), payload)
    }
    fun sessionStart(): ByteArray {
        val payload = byteArrayOf(1, 1, 3, 0, 1, 0, 0, 2, 2, 0, 0, 0xfc.toByte(), 3, 2, 0, 32, 0, 4, 2, 0, 0x10, 0x27)
        return frame(2, 0, payload)
    }
    fun validateSession(payload: ByteArray) {
        if (payload.isEmpty() || payload[0] != 2.toByte()) XiaomiProto.invalid()
        var position = 1
        val seen = mutableSetOf<Int>()
        while (position < payload.size) {
            if (payload.size - position < 3) XiaomiProto.invalid()
            val key = u(payload[position]); val size = le16(payload, position + 1)
            position += 3
            if (!seen.add(key) || size > payload.size - position) XiaomiProto.invalid()
            if (key == 1 && size != 3 || key in 2..4 && size != 2) XiaomiProto.invalid()
            if (key == 2 && le16(payload, position) < 256 || key == 3 && le16(payload, position) < 1) XiaomiProto.invalid()
            position += size
        }
    }
    fun crc16(input: ByteArray): Int {
        var crc = 0
        for (byte in input) {
            crc = crc xor u(byte)
            repeat(8) { crc = (crc ushr 1) xor if (crc and 1 == 0) 0 else 0xa001 }
        }
        return crc
    }
    fun u(value: Byte): Int = value.toInt() and 255
    fun le16(input: ByteArray, offset: Int): Int = u(input[offset]) or (u(input[offset + 1]) shl 8)
    private fun put16(output: ByteArray, offset: Int, value: Int) {
        output[offset] = value.toByte(); output[offset + 1] = (value ushr 8).toByte()
    }
    private fun exact(input: InputStream, size: Int): ByteArray {
        val result = ByteArray(size)
        var position = 0
        try {
            while (position < size) {
                val count = input.read(result, position, size - position)
                if (count <= 0) XiaomiProto.invalid()
                position += count
            }
            return result
        } catch (error: Exception) { result.fill(0); throw error }
    }
}
