package ru.kardiomonitor.bandprotocol.xiaomi

import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import ru.kardiomonitor.bandprotocol.xiaomi.XiaomiScriptedPeer.Companion.hex

class XiaomiWireTest {
    @Test fun `команды и кадры совпадают с проверенными каноническими байтами`() {
        assertArrayEquals(hex("badcfe00c00300000000ef"), XiaomiWire.versionRequest())
        assertArrayEquals(hex("a5a5010700000000"), XiaomiWire.frame(1, 7, ByteArray(0)))
        assertArrayEquals(hex("08021001"), XiaomiProto.command(2, 1))
        assertArrayEquals(hex("08021002"), XiaomiProto.command(2, 2))
        assertArrayEquals(hex("0808100a"), XiaomiProto.command(8, 10))
        XiaomiWire.read(ByteArrayInputStream(XiaomiWire.sessionStart())).use {
            assertArrayEquals(hex("0101030001000002020000fc03020020000402001027"), it.payload)
        }
        assertEquals(0xbb3d, XiaomiWire.crc16("123456789".toByteArray()))
    }
    @Test fun `версия нулевого канала не зависит от кода операции и типа данных`() {
        for (opcode in listOf(0, 1, 2, 255)) for (dataType in listOf(0, 1, 2, 255)) {
            val fixture = hex("badcfe00800600010000020061ef")
            fixture[7] = opcode.toByte(); fixture[9] = dataType.toByte()
            assertEquals(2, XiaomiWire.version(ByteArrayInputStream(fixture)))
        }
    }
    @Test fun `повреждённый ответ версии отклоняется без догадок`() {
        for (fixture in listOf("", "badcfe10800600010000020061ef", "badcfe00800200010000ef", "badcfe0080060001000002006100")) {
            assertThrows(IOException::class.java) { XiaomiWire.version(ByteArrayInputStream(hex(fixture))) }
        }
    }
    @Test fun `границы контрольная сумма тип и полнота кадра проверяются строго`() {
        val good = XiaomiWire.frame(3, 0, hex("01010801"))
        for (size in 0 until good.size) {
            assertThrows(IOException::class.java) { XiaomiWire.read(ByteArrayInputStream(good.copyOf(size))) }
        }
        val corrupted = good.copyOf(); corrupted[8] = 0
        for (fixture in listOf(corrupted, hex("a5a50300ffff0000"), hex("a5a5040000000000"), XiaomiWire.frame(1, 0, byteArrayOf(0)))) {
            assertThrows(IOException::class.java) { XiaomiWire.read(ByteArrayInputStream(fixture)) }
        }
        assertThrows(IOException::class.java) { XiaomiWire.frame(3, 0, ByteArray(4097)) }
    }
    @Test fun `последовательные кадры сохраняют свои границы`() {
        val frame = XiaomiWire.frame(1, 7, ByteArray(0))
        val stream = ByteArrayInputStream(frame + frame)
        repeat(2) { XiaomiWire.read(stream).use { assertEquals(7, it.sequence) } }
        assertEquals(0, stream.available())
    }
    @Test fun `поля сеанса ограничены без навязывания версии другой стороне`() {
        XiaomiWire.validateSession(hex("0201030002006102020000fc03020020000402001027"))
        for (fixture in listOf("", "01", "0201030001", "020202000100", "020302000000", "0202020000fc02020000fc")) {
            assertThrows(IOException::class.java) { XiaomiWire.validateSession(hex(fixture)) }
        }
    }
    @Test fun `протобуфер отклоняет повреждённые и повторные поля включая фиксированные`() {
        for (fixture in listOf("00", "08", "0a02ff", "08010802", "0f", "09", "08ffffffffffffffffffff02", "0d000000000800")) {
            assertThrows(IOException::class.java) { XiaomiProto.parse(hex(fixture)) }
        }
        assertThrows(IOException::class.java) { XiaomiProto.parse(ByteArray(4097)) }
        val fields = XiaomiProto.Writer().use { writer -> repeat(65) { writer.number(it + 1, 0) }; writer.build() }
        assertThrows(IOException::class.java) { XiaomiProto.parse(fields) }
        XiaomiProto.parse(hex("08001000")).use { assertEquals(0L, it.number(1)); assertEquals(0L, it.number(2)) }
    }
    @Test fun `фиксированное поле нельзя прочитать как вложенное сообщение`() {
        XiaomiProto.parse(hex("0d08011002")).use { command ->
            assertThrows(IOException::class.java) { command.message(1) }
            assertThrows(IOException::class.java) { command.number(1) }
        }
    }
    @Test fun `список разрешённых команд исключает историю запись настроек и запуск датчика`() {
        for (type in 0L..10L) for (subtype in 0L..60L) {
            val allowed = type == 2L && subtype in 1L..2L || type == 8L && subtype == 10L
            assertEquals(allowed, XiaomiSession.isAllowed(type, subtype, handshake = false))
            assertEquals(type == 1L && subtype in 26L..27L, XiaomiSession.isAllowed(type, subtype, handshake = true))
        }
        for (subtype in listOf(1L, 2L, 3L, 5L, 11L, 45L, 46L, 47L)) assertFalse(XiaomiSession.isAllowed(8, subtype, handshake = false))
    }
    @Test fun `вывод ключей и шифрование совпадают с независимыми векторами`() {
        assertArrayEquals(hex("d738074e6570abb50d001db70f497a37923e295e02aecb7619a8e1b9f574c9888676d22523869a15ee4340ac1bab5e35044aed283e7cace9aad41acb59fe75eb"), XiaomiCrypto.hkdf(
            hex("101112131415161718191a1b1c1d1e1f202122232425262728292a2b2c2d2e2f"), hex("000102030405060708090a0b0c0d0e0f"),
        ))
        assertArrayEquals(hex("3b15e667dca886ab7443bb8f608fa5606d7c1b609e50584a16"), XiaomiCrypto.ccm(
            hex("000102030405060708090a0b0c0d0e0f"), hex("000102030405060708090a0b"), hex("080015000028421a0550726f626520e0012a025255"),
        ))
    }
}
