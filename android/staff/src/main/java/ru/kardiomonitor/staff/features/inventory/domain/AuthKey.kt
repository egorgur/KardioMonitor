package ru.kardiomonitor.staff.features.inventory.domain

/** 16-байтовый ключ браслета. Значение не покидает scoped-вызов и не попадает в toString. */
class AuthKey private constructor(private val bytes: ByteArray) {
    fun <T> useBytes(block: (ByteArray) -> T): T {
        val copy = bytes.copyOf()
        return try {
            block(copy)
        } finally {
            copy.fill(0)
        }
    }

    suspend fun <T> useBytesSuspending(block: suspend (ByteArray) -> T): T {
        val copy = bytes.copyOf()
        return try {
            block(copy)
        } finally {
            copy.fill(0)
        }
    }

    fun <T> useHex(block: (String) -> T): T = useBytes { value ->
        block(value.joinToString("") { "%02x".format(it) })
    }

    override fun equals(other: Any?): Boolean = other is AuthKey && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "AuthKey(<hidden>)"

    companion object {
        private val EXACT = Regex("""[0-9a-f]{32}""")
        private val ANYWHERE = Regex("""(?i)(?<![0-9a-f])(?:0x)?([0-9a-f]{32})(?![0-9a-f])""")

        fun of(hex: String): AuthKey {
            val normalized = hex.trim().lowercase().removePrefix("0x")
            require(EXACT.matches(normalized)) { "Ключ должен содержать 32 hex-символа" }
            return AuthKey(normalized.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
        }

        fun fromBytes(bytes: ByteArray): AuthKey {
            require(bytes.size == 16) { "Ключ должен содержать 16 байт" }
            return AuthKey(bytes.copyOf())
        }

        fun findIn(text: String): AuthKey? = ANYWHERE.find(text)?.groupValues?.get(1)?.let(::of)
    }
}
