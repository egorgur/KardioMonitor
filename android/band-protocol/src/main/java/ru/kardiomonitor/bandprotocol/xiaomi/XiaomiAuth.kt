package ru.kardiomonitor.bandprotocol.xiaomi

import java.security.SecureRandom

/** Fresh nonce и constant-time проверка watch HMAC до подтверждения авторизации.
 * Поля и направления ключей: docs/band9-implementation-context.md §6.1, локальный Auth.java.
 */
internal class XiaomiAuth : AutoCloseable {
    private val phone = ByteArray(16).also { SecureRandom().nextBytes(it) }
    private var receiveKey: ByteArray? = null
    private var sendKey: ByteArray? = null
    private var sendPrefix: ByteArray? = null
    private var confirmed = false

    fun nonceCommand(): ByteArray {
        val verify = XiaomiProto.Writer().use { it.bytes(1, phone).build() }
        val account = XiaomiProto.Writer().use { it.bytes(30, verify).build() }
        try { return XiaomiProto.Writer().use { it.number(1, 1).number(2, 26).bytes(3, account).build() } }
        finally { verify.fill(0); account.fill(0) }
    }
    fun respond(secret: ByteArray, command: XiaomiProto, api: Int): ByteArray {
        if (secret.size != 16 || receiveKey != null || command.number(1) != 1L || command.number(2) != 26L) XiaomiProto.invalid()
        command.success()
        command.message(3).use { account ->
            account.message(31).use { verify ->
                val watch = verify.bytes(1)
                val proof = verify.bytes(2)
                if (watch.size != 16 || proof.size != 32) XiaomiProto.invalid()
                derive(secret, watch)
                val challenge = watch + phone
                val expected = try { XiaomiCrypto.hmac(requireNotNull(receiveKey), challenge) } finally { challenge.fill(0) }
                val valid = XiaomiCrypto.equals(proof, expected)
                expected.fill(0)
                if (!valid) { close(); XiaomiProto.invalid() }
                return confirmation(watch, api)
            }
        }
    }
    private fun derive(secret: ByteArray, watch: ByteArray) {
        val salt = phone + watch
        val expanded = try { XiaomiCrypto.hkdf(salt, secret) } finally { salt.fill(0) }
        try {
            receiveKey = expanded.copyOfRange(0, 16)
            sendKey = expanded.copyOfRange(16, 32)
            sendPrefix = expanded.copyOfRange(36, 40)
        } finally { expanded.fill(0) }
    }
    private fun confirmation(watch: ByteArray, api: Int): ByteArray {
        val garbage = mutableListOf<ByteArray>()
        try {
            val info = XiaomiProto.Writer().use {
                it.number(1, 0).float32(2, api.toFloat()).text(3, "Android")
                    .number(4, 224).text(5, "RU").build()
            }.also { garbage.add(it) }
            val nonce = ByteArray(12).also { requireNotNull(sendPrefix).copyInto(it); garbage.add(it) }
            val encrypted = XiaomiCrypto.ccm(requireNotNull(sendKey), nonce, info).also { garbage.add(it) }
            val both = (phone + watch).also { garbage.add(it) }
            val proof = XiaomiCrypto.hmac(requireNotNull(sendKey), both).also { garbage.add(it) }
            val confirm = XiaomiProto.Writer().use { it.bytes(1, proof).bytes(2, encrypted).build() }.also { garbage.add(it) }
            val account = XiaomiProto.Writer().use { it.bytes(32, confirm).build() }.also { garbage.add(it) }
            return XiaomiProto.Writer().use { it.number(1, 1).number(2, 27).bytes(3, account).build() }
        } finally { garbage.forEach { it.fill(0) } }
    }
    fun confirm(command: XiaomiProto) {
        command.success()
        if (receiveKey == null || command.number(1) != 1L || command.number(2) != 27L) XiaomiProto.invalid()
        if (command.has(3)) command.message(3).use {
            if (it.has(8) && it.number(8) !in 0L..1L) XiaomiProto.invalid()
        }
        confirmed = true
    }
    fun encrypt(input: ByteArray): ByteArray {
        if (!confirmed) XiaomiProto.invalid()
        return XiaomiCrypto.ctr(requireNotNull(sendKey), input)
    }
    fun decrypt(input: ByteArray, handshake: Boolean): ByteArray {
        if (!handshake && !confirmed) XiaomiProto.invalid()
        return XiaomiCrypto.ctr(receiveKey ?: XiaomiProto.invalid(), input)
    }
    override fun close() {
        phone.fill(0); receiveKey?.fill(0); sendKey?.fill(0); sendPrefix?.fill(0)
        receiveKey = null; sendKey = null; sendPrefix = null
        confirmed = false
    }
    override fun toString(): String = "XiaomiAuth"
}
