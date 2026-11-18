package ru.kardiomonitor.bandprotocol.xiaomi

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class XiaomiRfcommEndpointTest {
    private class Endpoint : XiaomiRfcommEndpoint {
        override var enabled = true
        override var bonded = true
        override var discovering = false
        var cancellationResult = false
        var cancelled = 0
        var created = 0
        val peer = XiaomiScriptedPeer()
        override fun cancelDiscovery(): Boolean { cancelled++; return cancellationResult }
        override fun createSecureTransport(): XiaomiTransport { created++; return peer }
    }
    @Test fun `ложный результат отмены поиска не мешает подключению когда поиск не активен`() = runBlocking {
        val endpoint = Endpoint()
        val client = XiaomiReadOnlyClient({ prepareXiaomiRfcomm(endpoint) }, api = 37)
        client.open("00:11:22:33:44:55", endpoint.peer.secret)
        assertEquals(73, client.readBattery())
        assertEquals(XiaomiHeartRateSettings(false, 10), client.readHeartRateSettings())
        client.close()
        assertFalse(endpoint.cancellationResult)
        assertEquals(1, endpoint.cancelled)
        assertEquals(1, endpoint.created)
        assertEquals(1, endpoint.peer.closeCount)
    }
    @Test fun `поиск отменяется даже если он уже не активен`() {
        for (result in listOf(false, true)) {
            val endpoint = Endpoint().apply { cancellationResult = result }
            assertSame(endpoint.peer, prepareXiaomiRfcomm(endpoint))
            assertEquals(1, endpoint.cancelled)
            assertEquals(1, endpoint.created)
        }
    }
    @Test fun `активный поиск после отмены запрещает создание сокета независимо от результата`() {
        for (result in listOf(false, true)) {
            val endpoint = Endpoint().apply { cancellationResult = result; discovering = true }
            assertThrows(IOException::class.java) { prepareXiaomiRfcomm(endpoint) }
            assertEquals(1, endpoint.cancelled)
            assertEquals(0, endpoint.created)
        }
    }
    @Test fun `выключенный адаптер и отсутствие сопряжения запрещают создание сокета`() {
        for (enabled in listOf(false, true)) {
            val endpoint = Endpoint().apply { this.enabled = enabled; bonded = false }
            assertThrows(IOException::class.java) { prepareXiaomiRfcomm(endpoint) }
            assertEquals(0, endpoint.cancelled)
            assertEquals(0, endpoint.created)
        }
    }
}
