package ru.kardiomonitor.staff.platform.keystore

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.security.GeneralSecurityException

@RunWith(AndroidJUnit4::class)
class KeystoreSecretBoxTest {
    private val alias = "staff-keystore-test-${java.util.UUID.randomUUID()}"
    private val secretBox = KeystoreSecretBox(alias)

    @org.junit.After
    fun removeOnlyTestAlias() {
        java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }

    @Test
    fun sealedTextOpensWithDeviceKey() {
        val plain = "0011223344556677".encodeToByteArray()
        assertArrayEquals(plain, secretBox.open(secretBox.seal(plain)))
    }

    @Test
    fun reopenedBoxUsesExistingAlias() {
        val plain = ByteArray(16) { it.toByte() }
        val sealed = secretBox.seal(plain)
        assertArrayEquals(plain, KeystoreSecretBox(alias).open(sealed))
    }

    @Test
    fun missingAliasDoesNotGenerateReplacementOnRead() {
        val sealed = secretBox.seal(ByteArray(16))
        val store = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        store.deleteEntry(alias)
        assertThrows(java.security.KeyStoreException::class.java) { KeystoreSecretBox(alias).open(sealed) }
        assertFalse(store.containsAlias(alias))
    }

    @Test
    fun invalidEnvelopeIsRejectedWithoutCreatingAlias() {
        assertThrows(IllegalArgumentException::class.java) { secretBox.open(byteArrayOf(1, 2)) }
        assertThrows(IllegalArgumentException::class.java) { secretBox.open(ByteArray(29) { 2 }) }
        val store = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertFalse(store.containsAlias(alias))
    }

    @Test
    fun equalTextGetsDifferentIv() {
        val plain = "0011223344556677".encodeToByteArray()
        assertFalse(secretBox.seal(plain).contentEquals(secretBox.seal(plain)))
    }

    @Test
    fun concurrentBoxesUseOneAliasWithoutLosingEarlierCiphertext() {
        val executor = java.util.concurrent.Executors.newFixedThreadPool(4)
        val plain = ByteArray(16) { it.toByte() }
        try {
            val tasks = List(8) { java.util.concurrent.Callable { KeystoreSecretBox(alias).seal(plain) } }
            executor.invokeAll(tasks).forEach { result ->
                assertArrayEquals(plain, KeystoreSecretBox(alias).open(result.get()))
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun tamperedCipherTextIsRejected() {
        val sealed = secretBox.seal("0011223344556677".encodeToByteArray())
        sealed[sealed.lastIndex] = (sealed.last().toInt() xor 1).toByte()

        assertThrows(GeneralSecurityException::class.java) { secretBox.open(sealed) }
    }
}
