package ru.kardiomonitor.platform.keystore

import junit.framework.TestCase
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.SecretKey

class AndroidKeystoreSecretBoxTest : TestCase() {
    override fun setUp() {
        super.setUp()
        deleteTestKey()
    }

    override fun tearDown() {
        deleteTestKey()
        super.tearDown()
    }

    fun testSecretCanOnlyBeOpenedWithKeystoreKey() {
        val box = AndroidKeystoreSecretBox(ALIAS)
        val plainText = "patient-secret".toByteArray()

        val first = box.seal(plainText)
        val second = box.seal(plainText)

        assertEquals("patient-secret", box.open(first).decodeToString())
        assertFalse(first.iv.contentEquals(second.iv))
        assertFalse(first.cipherText.contentEquals(plainText))
        val keyStore = keyStore()
        assertNull((keyStore.getKey(ALIAS, null) as SecretKey).encoded)
        keyStore.deleteEntry(ALIAS)
        try {
            box.open(first)
            fail("старый шифротекст открылся после удаления ключа")
        } catch (_: GeneralSecurityException) {
            assertFalse(keyStore().containsAlias(ALIAS))
        }
    }

    private fun deleteTestKey() {
        val keyStore = keyStore()
        if (keyStore.containsAlias(ALIAS)) keyStore.deleteEntry(ALIAS)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private companion object {
        const val ALIAS = "kardiomonitor.test.secrets"
    }
}
