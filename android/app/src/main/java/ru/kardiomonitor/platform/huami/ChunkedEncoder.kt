package ru.kardiomonitor.platform.huami

private const val MIN_MTU = 23

/**
 * Разбивает сообщение канала Huami 2021 на части для характеристики 0x0016 (docs/ble-protocol.md).
 * Без шифрования — для авторизации хватает. Gadgetbridge Huami2021ChunkedEncoder.
 */
internal class ChunkedEncoder(mtu: Int = MIN_MTU) {
    private val maxChunk = minOf(512, maxOf(MIN_MTU, mtu) - 3)
    private var handle = 0

    fun encode(endpoint: Int, data: ByteArray): List<ByteArray> {
        handle = (handle + 1) and 0xFF
        val chunks = mutableListOf<ByteArray>()
        var offset = 0
        var count = 0
        while (offset < data.size || count == 0) {
            val first = count == 0
            val headerSize = if (first) 11 else 5
            val end = minOf(data.size, offset + maxChunk - headerSize)
            val part = data.copyOfRange(offset, end)
            offset = end
            var flags = if (first) 0x01 else 0x00
            if (offset >= data.size) flags = flags or 0x02 or 0x04 // последний кусок, нужен ack
            var header = byteArrayOf(0x03, flags.toByte(), 0x00, handle.toByte(), count.toByte())
            if (first) header += le32(data.size) + byteArrayOf(endpoint.toByte(), (endpoint shr 8).toByte())
            chunks += header + part
            count++
        }
        return chunks
    }
}
