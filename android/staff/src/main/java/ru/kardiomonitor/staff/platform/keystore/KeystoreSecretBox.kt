package ru.kardiomonitor.staff.platform.keystore

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class KeystoreSecretBox internal constructor(private val keyAlias: String) : SecretBox {
    constructor() : this(KEY_ALIAS)
    override fun seal(plainText: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        return byteArrayOf(FORMAT_VERSION) + cipher.iv + cipher.doFinal(plainText)
    }

    override fun open(sealed: ByteArray): ByteArray {
        require(sealed.size >= HEADER_SIZE + TAG_BITS / 8) { "Повреждённый шифротекст" }
        require(sealed.first() == FORMAT_VERSION) { "Неизвестный формат шифротекста" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val iv = sealed.copyOfRange(1, HEADER_SIZE)
        val key = existingKey() ?: throw java.security.KeyStoreException("Ключ хранилища недоступен")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(sealed.copyOfRange(HEADER_SIZE, sealed.size))
    }

    private fun existingKey(): SecretKey? =
        KeyStore.getInstance(KEYSTORE).apply { load(null) }.getKey(keyAlias, null) as? SecretKey

    private fun encryptionKey(): SecretKey = synchronized(KEY_LOCK) {
        existingKey() ?: createKey()
    }

    private fun createKey(): SecretKey {
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setUnlockedDeviceRequired(true)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(spec)
            generateKey()
        }
    }

    private companion object {
        val KEY_LOCK = Any()
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "kardiomonitor_staff_inventory_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val HEADER_SIZE = 1 + IV_BYTES
        const val TAG_BITS = 128
        const val FORMAT_VERSION: Byte = 1
    }
}
