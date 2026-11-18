package ru.kardiomonitor.bandprotocol.xiaomi

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.kardiomonitor.bandprotocol.xiaomi.XiaomiScriptedPeer.Companion.hex

class XiaomiSessionTest {
    private fun session(peer: XiaomiScriptedPeer): XiaomiSession = XiaomiSession(peer.input, peer.output)
    @Test fun `чтение доступно после авторизации и проверки устройства`() {
        val peer = XiaomiScriptedPeer()
        session(peer).use {
            it.open(peer.secret, 37)
            assertEquals(73, it.battery())
            assertEquals(XiaomiHeartRateSettings(false, 10), it.heartRateSettings())
        }
        assertTrue(peer.companionVerified)
        assertEquals(listOf(1L to 26L, 1L to 27L, 2L to 2L, 2L to 1L, 8L to 10L), peer.commands)
        assertTrue(peer.transportAcks >= 5)
    }
    @Test fun `зашифрованное подтверждение и повторные транспортные кадры поддерживаются`() {
        val peer = XiaomiScriptedPeer().apply { encryptedConfirmation = true; duplicate = true }
        session(peer).use { it.open(peer.secret, 37); assertEquals(73, it.battery()) }
        assertEquals(1, peer.commands.count { it == 2L to 1L })
    }
    @Test fun `неверная подпись браслета запрещает подтверждение и чтение`() {
        val peer = XiaomiScriptedPeer().apply { badProof = true }
        session(peer).use { current -> assertThrows(IOException::class.java) { current.open(peer.secret, 37) } }
        assertEquals(listOf(1L to 26L), peer.commands)
    }
    @Test fun `отклонённое подтверждение не открывает доступ к чтению`() {
        val peer = XiaomiScriptedPeer().apply { rejectConfirmation = true }
        session(peer).use { current ->
            assertThrows(IOException::class.java) { current.open(peer.secret, 37) }
            assertThrows(IOException::class.java) { current.battery() }
        }
        assertEquals(listOf(1L to 26L, 1L to 27L), peer.commands)
    }
    @Test fun `точная модель и прошивка обязательны для каждого поддержанного чтения`() {
        for ((model, firmware) in listOf("M2345B1" to "2.3.99", "M2345B1 Pro" to "2.3.98", "M2345B2" to "2.3.98")) {
            val peer = XiaomiScriptedPeer().apply { this.model = model; this.firmware = firmware }
            session(peer).use { current ->
                assertThrows(IOException::class.java) { current.open(peer.secret, 37) }
                assertThrows(IOException::class.java) { current.battery() }
                assertThrows(IOException::class.java) { current.heartRateSettings() }
            }
            assertFalse(peer.commands.contains(2L to 1L))
            assertFalse(peer.commands.contains(8L to 10L))
        }
    }
    @Test fun `отсутствующий заряд не равен нулю и выход за диапазон отклоняется`() {
        val absent = XiaomiScriptedPeer().apply { batteryAbsent = true }
        session(absent).use { it.open(absent.secret, 37); assertNull(it.battery()) }
        val zero = XiaomiScriptedPeer().apply { batteryValue = 0 }
        session(zero).use { it.open(zero.secret, 37); assertEquals(0, it.battery()) }
        for (value in listOf(-1L, 101L)) {
            val peer = XiaomiScriptedPeer().apply { batteryValue = value }
            session(peer).use { current ->
                current.open(peer.secret, 37)
                assertThrows(IOException::class.java) { current.battery() }
            }
        }
    }
    @Test fun `умный интервал имеет явный ноль а отсутствие поля сохраняется`() {
        for ((body, expected) in listOf(
            "08001000" to XiaomiHeartRateSettings(false, 0),
            "0801" to XiaomiHeartRateSettings(true, null),
            "100a" to XiaomiHeartRateSettings(null, 10),
            "" to XiaomiHeartRateSettings(null, null),
        )) {
            val peer = XiaomiScriptedPeer().apply { settingsBody = hex(body) }
            session(peer).use { it.open(peer.secret, 37); assertEquals(expected, it.heartRateSettings()) }
        }
        val peer = XiaomiScriptedPeer().apply { settingsHealthAbsent = true }
        session(peer).use { it.open(peer.secret, 37); assertEquals(XiaomiHeartRateSettings(null, null), it.heartRateSettings()) }
    }
    @Test fun `повреждённые настройки не превращаются в значения по умолчанию`() {
        for (body in listOf("0802", "10ffffffff0f", "0a00", "08000801")) {
            val peer = XiaomiScriptedPeer().apply { settingsBody = hex(body) }
            session(peer).use { current ->
                current.open(peer.secret, 37)
                assertThrows(IOException::class.java) { current.heartRateSettings() }
            }
        }
    }
    @Test fun `отказ пропуск номера и незашифрованный ответ прерывают сеанс без повтора`() {
        for (mode in 0..2) {
            val peer = XiaomiScriptedPeer().apply {
                nack = mode == 0; sequenceGap = mode == 1; plaintextResponse = mode == 2
            }
            session(peer).use { current -> assertThrows(IOException::class.java) { current.open(peer.secret, 37) } }
            assertTrue(peer.commands.count { it == 1L to 26L } <= 1)
        }
    }
    @Test fun `переполнение номера и накопительные транспортные подтверждения поддерживаются`() {
        val peer = XiaomiScriptedPeer().apply { cumulativeAcks = true }
        session(peer).use { current ->
            current.open(peer.secret, 37)
            repeat(300) { assertEquals(73, current.battery()) }
        }
    }
    @Test fun `число входящих кадров за сеанс ограничено`() {
        val peer = XiaomiScriptedPeer()
        session(peer).use { current ->
            current.open(peer.secret, 37)
            repeat(252) { assertEquals(73, current.battery()) }
            assertThrows(IOException::class.java) { current.battery() }
        }
    }
    @Test fun `повторное открытие и использование закрытого сеанса запрещены`() {
        val peer = XiaomiScriptedPeer()
        val current = session(peer)
        current.open(peer.secret, 37)
        assertThrows(IOException::class.java) { current.open(peer.secret, 37) }
        current.close()
        assertThrows(IOException::class.java) { current.open(peer.secret, 37) }
    }
    @Test fun `чтение до авторизации и после закрытия запрещено`() {
        val peer = XiaomiScriptedPeer()
        session(peer).use { current ->
            assertThrows(IOException::class.java) { current.battery() }
            assertThrows(IOException::class.java) { current.heartRateSettings() }
            current.open(peer.secret, 37); current.close()
            assertThrows(IOException::class.java) { current.battery() }
        }
    }
}
