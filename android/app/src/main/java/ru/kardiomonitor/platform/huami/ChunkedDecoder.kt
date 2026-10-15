package ru.kardiomonitor.platform.huami

import java.io.ByteArrayOutputStream

/**
 * Собирает сообщение из кусков характеристики 0x0017. Зашифрованные сообщения не расшифровываются,
 * но подтверждение (ack) для них формируется — иначе браслет будет повторять отправку.
 * Gadgetbridge Huami2021ChunkedDecoder.
 */
internal class ChunkedDecoder {
    class Message(val endpoint: Int, val payload: ByteArray, val encrypted: Boolean, val ack: ByteArray?)

    private val buffer = ByteArrayOutputStream()
    private var length = 0
    private var endpoint = 0

    fun feed(data: ByteArray): Message? {
        if (data.size < 5 || data[0] != 0x03.toByte()) return null
        val flags = data[1].toInt() and 0xFF
        val handle = data[3]
        val count = data[4]
        val encrypted = flags and 0x08 != 0
        var bodyStart = 5
        if (flags and 0x01 != 0) {
            if (data.size < 11) return null
            length = le32(data, 5)
            endpoint = le16(data, 9)
            buffer.reset()
            bodyStart = 11
        }
        buffer.write(data, bodyStart, data.size - bodyStart)
        if (flags and 0x02 == 0) return null
        val ack = if (flags and 0x04 != 0) byteArrayOf(0x04, 0x00, handle, 0x01, count) else null
        val all = buffer.toByteArray()
        val payload = if (encrypted) all else all.copyOf(minOf(length, all.size))
        return Message(endpoint, payload, encrypted, ack)
    }
}
