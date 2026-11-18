package ru.kardiomonitor.bandprotocol.xiaomi

import java.io.IOException
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaomiReadOnlyClientTest {
    private val address = "00:11:22:33:44:55"
    private fun client(peer: XiaomiScriptedPeer, timeout: Long = 1_000): XiaomiReadOnlyClient =
        XiaomiReadOnlyClient({ peer }, api = 37, openTimeoutMillis = timeout, readTimeoutMillis = timeout)
    private suspend fun await(latch: CountDownLatch) {
        withTimeout(3_000) { while (latch.count > 0) delay(1) }
    }
    private suspend fun ioFailure(block: suspend () -> Unit): IOException {
        try { block() } catch (error: IOException) { return error }
        throw AssertionError("Expected IOException")
    }
    @Test fun `публичный клиент читает данные и допускает повторное закрытие`(): Unit = runBlocking {
        val peer = XiaomiScriptedPeer()
        val original = peer.secret.copyOf()
        val client = client(peer)
        client.open(address, peer.secret)
        assertEquals(73, client.readBattery())
        assertEquals(XiaomiHeartRateSettings(false, 10), client.readHeartRateSettings())
        assertArrayEquals(original, peer.secret)
        client.close(); client.close()
        assertEquals(1, peer.closeCount)
        ioFailure { client.readBattery() }
    }
    @Test fun `неверные параметры не создают транспорт`() = runBlocking {
        var created = false
        val client = XiaomiReadOnlyClient({ created = true; XiaomiScriptedPeer() }, api = 37)
        ioFailure { client.open("invalid", ByteArray(16)) }
        ioFailure { client.open(address, ByteArray(15)) }
        assertFalse(created)
    }
    @Test fun `отмена подключения закрывает сокет и сохраняет отмену`(): Unit = runBlocking {
        val peer = XiaomiScriptedPeer().apply { blockConnect = true }
        val client = client(peer)
        val task = async { client.open(address, peer.secret) }
        await(peer.connecting)
        task.cancelAndJoin()
        assertTrue(task.isCancelled)
        assertEquals(1, peer.closeCount)
        ioFailure { client.readBattery() }
    }
    @Test fun `таймаут подключения закрывает сокет и возвращает ошибку ввода вывода`() = runBlocking {
        val peer = XiaomiScriptedPeer().apply { blockConnect = true }
        val client = client(peer, timeout = 50)
        val error = ioFailure { client.open(address, peer.secret) }
        assertEquals(1, peer.closeCount)
        assertNull(error.cause)
    }
    @Test fun `отмена блокирующего чтения закрывает сокет и сохраняет отмену`(): Unit = runBlocking {
        val peer = XiaomiScriptedPeer()
        val client = client(peer)
        client.open(address, peer.secret)
        peer.omitBatteryReply = true
        val task = async { client.readBattery() }
        await(peer.reading)
        task.cancelAndJoin()
        assertTrue(task.isCancelled)
        assertEquals(1, peer.closeCount)
        ioFailure { client.readHeartRateSettings() }
    }
    @Test fun `таймаут блокирующего чтения закрывает сокет без подмены отсутствующего значения`() = runBlocking {
        val peer = XiaomiScriptedPeer()
        val client = client(peer, timeout = 100)
        client.open(address, peer.secret)
        peer.batteryAbsent = true
        assertNull(client.readBattery())
        peer.omitBatteryReply = true
        ioFailure { client.readBattery() }
        assertEquals(1, peer.closeCount)
    }
    @Test fun `явное закрытие прерывает блокирующее чтение и подключение`() = runBlocking {
        for (blockConnect in listOf(false, true)) {
            val peer = XiaomiScriptedPeer().apply { this.blockConnect = blockConnect }
            val client = client(peer)
            var notifications = 0
            client.onConnectionLost = { notifications++ }
            if (!blockConnect) client.open(address, peer.secret)
            peer.omitBatteryReply = !blockConnect
            val task = async {
                ioFailure { if (blockConnect) client.open(address, peer.secret) else client.readBattery() }
            }
            await(if (blockConnect) peer.connecting else peer.reading)
            client.close()
            task.await()
            assertEquals(1, peer.closeCount)
            assertEquals(0, notifications)
        }
    }
    @Test fun `ошибки не раскрывают данные а неверная прошивка закрывает сокет`() = runBlocking {
        val peer = XiaomiScriptedPeer().apply { firmware = "2.3.99" }
        val client = client(peer)
        val error = ioFailure { client.open(address, peer.secret) }
        assertEquals(1, peer.closeCount)
        assertNull(error.cause)
        assertFalse(error.toString().contains(address))
        assertFalse(error.toString().contains("2.3.99"))
        assertEquals("XiaomiReadOnlyClient", client.toString())
        val unavailable = XiaomiReadOnlyClient({ throw SecurityException("sensitive-address") }, api = 37)
        val denied = ioFailure { unavailable.open(address, peer.secret) }
        assertNull(denied.cause)
        assertFalse(denied.toString().contains("sensitive"))
    }
    @Test fun `этап ошибки открытия фиксирован и не содержит исходного исключения`() = runBlocking {
        val modes = listOf(
            XiaomiScriptedPeer().apply { blockConnect = true } to XiaomiOpenPhase.SOCKET_CONNECT,
            XiaomiScriptedPeer().apply { version = 1 } to XiaomiOpenPhase.VERSION,
            XiaomiScriptedPeer().apply { malformedSession = true } to XiaomiOpenPhase.SESSION,
            XiaomiScriptedPeer().apply { nack = true } to XiaomiOpenPhase.AUTH_CHALLENGE,
            XiaomiScriptedPeer().apply { sequenceGap = true } to XiaomiOpenPhase.AUTH_CHALLENGE,
            XiaomiScriptedPeer().apply { badProof = true } to XiaomiOpenPhase.WATCH_PROOF,
            XiaomiScriptedPeer().apply { rejectConfirmation = true } to XiaomiOpenPhase.AUTH_CONFIRM,
            XiaomiScriptedPeer().apply { plaintextResponse = true } to XiaomiOpenPhase.DEVICE_INFO,
            XiaomiScriptedPeer().apply { firmware = "2.3.99" } to XiaomiOpenPhase.IDENTITY_GUARD,
        )
        for ((peer, expected) in modes) {
            val current = client(peer, timeout = 100)
            val error = ioFailure { current.open(address, peer.secret) }
            assertTrue(error is XiaomiOpenException)
            assertEquals(expected, (error as XiaomiOpenException).phase)
            assertNull(error.cause)
            assertEquals(1, peer.closeCount)
        }
    }
    @Test fun `ошибка чтения уведомляет владельца один раз после закрытия сокета`() = runBlocking {
        val peer = XiaomiScriptedPeer()
        val client = client(peer)
        var notifications = 0
        client.onConnectionLost = {
            assertEquals(1, peer.closeCount)
            notifications++
        }
        client.open(address, peer.secret)
        peer.batteryValue = 101
        ioFailure { client.readBattery() }
        ioFailure { client.readHeartRateSettings() }
        client.close()
        assertEquals(1, notifications)
    }
    @Test fun `ошибка открытия и явное закрытие не уведомляют о потере открытого сеанса`() = runBlocking {
        val peer = XiaomiScriptedPeer().apply { badProof = true }
        val client = client(peer)
        var notifications = 0
        client.onConnectionLost = { notifications++ }
        ioFailure { client.open(address, peer.secret) }
        client.close()
        assertEquals(0, notifications)
        val opened = client(XiaomiScriptedPeer())
        opened.onConnectionLost = { notifications++ }
        opened.open(address, peer.secret)
        opened.close()
        assertEquals(0, notifications)
    }
    @Test fun `таймаут чтения уведомляет владельца ровно один раз`() = runBlocking {
        val peer = XiaomiScriptedPeer()
        val client = client(peer, timeout = 100)
        var notifications = 0
        client.onConnectionLost = { notifications++ }
        client.open(address, peer.secret)
        peer.omitBatteryReply = true
        ioFailure { client.readBattery() }
        ioFailure { client.readBattery() }
        assertEquals(1, notifications)
    }
    @Test fun `исключение уведомления не подменяет отмену чтения`() = runBlocking {
        val peer = XiaomiScriptedPeer()
        val client = client(peer)
        var notifications = 0
        client.onConnectionLost = { notifications++; throw IllegalStateException("Ошибка владельца") }
        client.open(address, peer.secret)
        peer.omitBatteryReply = true
        val task = async { client.readBattery() }
        await(peer.reading)
        task.cancelAndJoin()
        assertTrue(task.isCancelled)
        assertEquals(1, notifications)
        assertEquals(1, peer.closeCount)
    }
    @Test fun `повторное открытие использует новый транспорт и закрывает предыдущий`() = runBlocking {
        val first = XiaomiScriptedPeer()
        val second = XiaomiScriptedPeer()
        var count = 0
        val client = XiaomiReadOnlyClient({ if (count++ == 0) first else second }, api = 37)
        client.open(address, first.secret)
        client.open(address, second.secret)
        assertEquals(1, first.closeCount)
        assertEquals(73, client.readBattery())
        client.close()
        assertEquals(1, second.closeCount)
    }
}
