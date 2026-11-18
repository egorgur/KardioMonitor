package ru.kardiomonitor.platform.keystore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SealedSecretTest {
    @Test
    fun `зашифрованный секрет сохраняется и читается без потери байтов`() {
        val original = SealedSecret(ByteArray(12) { it.toByte() }, byteArrayOf(12, 34, 56, 78))

        assertEquals(original, SealedSecret.decode(original.encode()))
    }

    @Test
    fun `текстовое представление не раскрывает шифротекст`() {
        val secret = SealedSecret(ByteArray(12), "private-token".toByteArray())

        assertFalse(secret.toString().contains("private-token"))
    }
}
