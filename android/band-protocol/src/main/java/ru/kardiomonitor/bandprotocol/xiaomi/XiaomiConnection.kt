package ru.kardiomonitor.bandprotocol.xiaomi

import java.io.IOException

/** close не ждёт блокирующую операцию: закрытие socket прерывает connect/read/write. */
internal class XiaomiConnection {
    private var transport: XiaomiTransport? = null
    private var session: XiaomiSession? = null
    private var closed = false
    private var busy = false

    @Synchronized fun begin() {
        if (closed || busy) throw IOException("Сеанс Xiaomi недоступен")
        busy = true
    }
    @Synchronized fun attach(value: XiaomiTransport) {
        if (closed) {
            closeTransport(value)
            throw IOException("Сеанс Xiaomi закрыт")
        }
        transport = value
    }
    @Synchronized fun attach(value: XiaomiSession) {
        if (closed) { value.close(); throw IOException("Сеанс Xiaomi закрыт") }
        session = value
    }
    @Synchronized fun session(): XiaomiSession {
        if (closed) throw IOException("Сеанс Xiaomi закрыт")
        return session ?: throw IOException("Сеанс Xiaomi не открыт")
    }
    @Synchronized fun end() {
        busy = false
        if (closed) { session?.close(); session = null }
    }
    fun close() {
        val socket = synchronized(this) {
            closed = true
            val value = transport
            transport = null
            if (!busy) { session?.close(); session = null }
            value
        }
        if (socket != null) closeTransport(socket)
    }
    private fun closeTransport(value: XiaomiTransport) {
        // Низкоуровневый текст ошибки может содержать адрес; закрытие уже помечено в состоянии.
        try { value.close() } catch (_: Exception) { /* Закрытый сеанс повторно не используется. */ }
    }
    override fun toString(): String = "XiaomiConnection"
}
