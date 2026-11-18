package ru.kardiomonitor.platform.keystore

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-256-GCM с неэкспортируемым ключом Android Keystore. */
class AndroidKeystoreSecretBox(private val alias: String) : SecretBox {
    override fun seal(plainText: ByteArray): SealedSecret {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return SealedSecret(cipher.iv, cipher.doFinal(plainText))
    }

    override fun open(secret: SealedSecret): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val existing = KeyStore.getInstance(KEYSTORE).apply { load(null) }.getKey(alias, null) as? SecretKey
            ?: throw UnrecoverableKeyException("ключ хранилища недоступен")
        cipher.init(Cipher.DECRYPT_MODE, existing, GCMParameterSpec(TAG_BITS, secret.iv))
        return cipher.doFinal(secret.cipherText)
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(alias, null) as? SecretKey
        return existing ?: createKey()
    }

    private fun createKey(): SecretKey {
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setKeySize(KEY_BITS)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(builder.build())
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BITS = 256
        const val TAG_BITS = 128
    }
}
