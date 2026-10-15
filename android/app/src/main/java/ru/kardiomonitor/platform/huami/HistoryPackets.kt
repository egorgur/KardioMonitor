package ru.kardiomonitor.platform.huami

import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Сборка порции истории из пакетов характеристики 0x0005: `[счётчик пакета][данные…]`,
 * счётчик растёт на 1 по модулю 256; пропуск счётчика — порция испорчена
 * (docs/ble-protocol.md, «История активности»). Пакеты приходят в потоке Binder.
 */
internal class HistoryPackets {
    private val buffer = ByteArrayOutputStream()
    private var lastCounter = -1
    private var isBroken = false

    @Synchronized
    fun reset() {
        buffer.reset()
        lastCounter = -1
        isBroken = false
    }

    @Synchronized
    fun add(packet: ByteArray) {
        if (packet.isEmpty()) {
            isBroken = true
            return
        }
        val counter = packet[0].toInt() and 0xFF
        if (counter != (lastCounter + 1) and 0xFF) {
            isBroken = true
        } else {
            lastCounter = counter
            buffer.write(packet, 1, packet.size - 1)
        }
    }

    /** Данные порции без счётчиков. */
    @Synchronized
    fun records(): ByteArray {
        if (isBroken) throw IOException("история пришла с пропусками пакетов")
        return buffer.toByteArray()
    }
}
