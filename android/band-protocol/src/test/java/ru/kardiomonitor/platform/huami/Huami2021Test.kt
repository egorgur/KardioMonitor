package ru.kardiomonitor.platform.huami

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import ru.kardiomonitor.platform.ble.hexToBytes
import ru.kardiomonitor.platform.ble.toHex

class Huami2021Test {
    private val priv = "3c1f8a9b2d4e5f60718293a4b5c6d7e8f90112233445566f".hexToBytes()
    private val priv2 = "a1b2c3d4e5f60718293a4b5c6d7e8f9001122334455667f8".hexToBytes()
    private val pub = "628d59384404de98fcb2c44e5851b80373eb63fb0700000077ae687ab29d64dffcef1a3984c967b40bc028b705000000"
    private val pub2 = "d7ee0005e24414c7215c9bdb319e244387d32e1e0600000078a183e6017d71ca077ba94e2855ef4ed97d236206000000"
    private val shared = "bbd1f5054eab024af71fe58209a80f386c4abf1a00000000c42f037a7edd50bda7282a4b5ae9370bcbfb207d04000000"

    @Test fun `открытый ключ ECDH совпадает с эталоном Python`() {
        assertEquals(pub, EcdhB163.publicKey(priv).toHex())
        assertEquals(pub2, EcdhB163.publicKey(priv2).toHex())
    }

    @Test fun `общий секрет совпадает с эталоном и одинаков у обеих сторон`() {
        assertEquals(shared, EcdhB163.sharedSecret(priv, pub2.hexToBytes()).toHex())
        assertArrayEquals(
            EcdhB163.sharedSecret(priv, pub2.hexToBytes()).copyOf(24),
            EcdhB163.sharedSecret(priv2, pub.hexToBytes()).copyOf(24),
        )
    }

    @Test fun `случайные пары ключей дают одинаковый общий секрет`() {
        val k1 = EcdhB163.generatePrivateKey()
        val k2 = EcdhB163.generatePrivateKey()
        assertArrayEquals(
            EcdhB163.sharedSecret(k1, EcdhB163.publicKey(k2)).copyOf(24),
            EcdhB163.sharedSecret(k2, EcdhB163.publicKey(k1)).copyOf(24),
        )
    }

    @Test fun `разбиение сообщения на части совпадает с эталоном`() {
        val chunks = ChunkedEncoder().encode(ENDPOINT_AUTH, ByteArray(52) { it.toByte() }).map { it.toHex() }
        assertEquals(listOf(
            "0301000100340000008200000102030405060708",
            "0300000101090a0b0c0d0e0f1011121314151617",
            "030000010218191a1b1c1d1e1f20212223242526",
            "03060001032728292a2b2c2d2e2f30313233",
        ), chunks)
    }

    @Test fun `сборка частей восстанавливает исходное сообщение`() {
        val data = ByteArray(52) { it.toByte() }
        val decoder = ChunkedDecoder()
        val message = ChunkedEncoder().encode(ENDPOINT_AUTH, data).mapNotNull(decoder::feed).single()
        assertEquals(ENDPOINT_AUTH, message.endpoint)
        assertArrayEquals(data, message.payload)
        assertEquals("0400010103", message.ack!!.toHex())
    }

    @Test fun `ответ на вызов авторизации совпадает с эталоном`() {
        val key = "00112233445566778899aabbccddeeff".hexToBytes()
        val challenge = byteArrayOf(0x10, 0x04, 0x01) + ByteArray(16) { it.toByte() } + pub2.hexToBytes()
        assertEquals(
            "05279fb74a7572135e8f9b8ef6d1eee003c3aa05d6910af846dd88e3e72a8d2c60",
            Auth2021(key, priv).answerChallenge(challenge).toHex(),
        )
    }
}
