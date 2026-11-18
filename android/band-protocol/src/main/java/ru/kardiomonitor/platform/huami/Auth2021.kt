package ru.kardiomonitor.platform.huami

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

internal const val ENDPOINT_AUTH = 0x0082

/** Авторизация Huami 2021 для Mi Band 6. */
internal class Auth2021(private val authKey: ByteArray, privateKey: ByteArray? = null) {
    private val privateKey = privateKey ?: EcdhB163.generatePrivateKey()
    val publicKey: ByteArray = EcdhB163.publicKey(this.privateKey)

    fun pubkeyCommand(): ByteArray = byteArrayOf(0x04, 0x02, 0x00, 0x02) + publicKey

    fun answerChallenge(payload: ByteArray): ByteArray {
        val remoteRandom = payload.copyOfRange(3, 19)
        val shared = EcdhB163.sharedSecret(privateKey, payload.copyOfRange(19, 67))
        val sessionKey = ByteArray(16) { (shared[it + 8].toInt() xor authKey[it].toInt()).toByte() }
        return byteArrayOf(0x05) + aesEcb(remoteRandom, authKey) + aesEcb(remoteRandom, sessionKey)
    }
}

internal fun aesEcb(data: ByteArray, key: ByteArray): ByteArray =
    Cipher.getInstance("AES/ECB/NoPadding").run {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        doFinal(data)
    }
