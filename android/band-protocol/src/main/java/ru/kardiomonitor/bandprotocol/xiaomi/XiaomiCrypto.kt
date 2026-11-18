package ru.kardiomonitor.bandprotocol.xiaomi

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** JCA и узкий CCM без AAD, nonce=12, tag=4; Android не гарантирует CCM provider.
 * Источник: оригинальный локальный Crypto.java, RFC 3610; это не новая криптосистема.
 */
internal object XiaomiCrypto {
    fun hmac(key: ByteArray, input: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(input)
    }
    fun hkdf(salt: ByteArray, secret: ByteArray): ByteArray {
        val key = hmac(salt, secret)
        val info = "miwear-auth".toByteArray(Charsets.US_ASCII)
        var previous = ByteArray(0)
        val result = ByteArray(64)
        try {
            for (block in 1..2) {
                val input = previous + info + byteArrayOf(block.toByte())
                val next = try { hmac(key, input) } finally { input.fill(0) }
                previous.fill(0); previous = next
                previous.copyInto(result, (block - 1) * 32)
            }
            return result
        } finally { key.fill(0); previous.fill(0) }
    }
    fun ctr(key: ByteArray, input: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(key))
        return cipher.doFinal(input)
    }
    // ECB здесь только AES block primitive для CCM, не режим шифрования сообщений.
    @Suppress("GetInstance")
    fun ccm(key: ByteArray, nonce: ByteArray, plain: ByteArray): ByteArray {
        if (key.size != 16 || nonce.size != 12 || plain.size > 4096) XiaomiProto.invalid()
        val aes = Cipher.getInstance("AES/ECB/NoPadding")
        aes.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        val first = counter(nonce, plain.size)
        first[0] = (first[0].toInt() or 8).toByte()
        var state = aes.doFinal(first)
        val result = ByteArray(plain.size + 4)
        try {
            for (offset in plain.indices step 16) {
                val count = minOf(16, plain.size - offset)
                for (index in 0 until count) state[index] = (state[index].toInt() xor plain[offset + index].toInt()).toByte()
                val next = aes.doFinal(state)
                state.fill(0); state = next
                val mask = aes.doFinal(counter(nonce, offset / 16 + 1))
                for (index in 0 until count) result[offset + index] = (plain[offset + index].toInt() xor mask[index].toInt()).toByte()
                mask.fill(0)
            }
            val s0 = aes.doFinal(counter(nonce, 0))
            for (index in 0..3) result[plain.size + index] = (state[index].toInt() xor s0[index].toInt()).toByte()
            s0.fill(0)
            return result
        } finally { state.fill(0); first.fill(0) }
    }
    fun equals(first: ByteArray, second: ByteArray): Boolean = MessageDigest.isEqual(first, second)
    private fun counter(nonce: ByteArray, value: Int): ByteArray {
        val result = ByteArray(16)
        result[0] = 2
        nonce.copyInto(result, 1)
        result[13] = (value ushr 16).toByte(); result[14] = (value ushr 8).toByte(); result[15] = value.toByte()
        return result
    }
}
