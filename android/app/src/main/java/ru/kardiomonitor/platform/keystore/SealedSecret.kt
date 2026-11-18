package ru.kardiomonitor.platform.keystore

import java.util.Base64

/** IV и шифротекст AES-GCM. Текстовое представление не раскрывает содержимое. */
data class SealedSecret(val iv: ByteArray, val cipherText: ByteArray) {
    init {
        require(iv.size == IV_BYTES) { "неверная длина IV" }
        require(cipherText.isNotEmpty()) { "пустой шифротекст" }
    }

    fun encode(): String = "${base64(iv)}.${base64(cipherText)}"

    override fun toString(): String = "SealedSecret(iv=${iv.size} bytes, cipherText=${cipherText.size} bytes)"

    override fun equals(other: Any?): Boolean =
        other is SealedSecret && iv.contentEquals(other.iv) && cipherText.contentEquals(other.cipherText)

    override fun hashCode(): Int = 31 * iv.contentHashCode() + cipherText.contentHashCode()

    companion object {
        private const val IV_BYTES = 12

        fun decode(encoded: String): SealedSecret {
            val parts = encoded.split('.', limit = 2)
            require(parts.size == 2) { "неверный формат зашифрованного секрета" }
            return SealedSecret(unbase64(parts[0]), unbase64(parts[1]))
        }

        private fun base64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        private fun unbase64(value: String): ByteArray = Base64.getUrlDecoder().decode(value)
    }
}
