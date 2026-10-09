package ru.kardiomonitor.ble

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Авторизация Huami 2021 (Mi Band 6 с прошивкой 1.0.6.10+).
 * Порт huami2021.py (а тот — из Gadgetbridge: InitOperation2021, Huami2021Chunked*, ECDH_B163).
 */

/** ECDH на кривой NIST B-163: y^2 + xy = x^3 + x^2 + b над GF(2^163). */
object EcdhB163 {
    const val KEY_BYTES = 24 // 6 x uint32, little-endian

    private const val DEGREE = 163
    private val ONE = BigInteger.ONE
    private val ZERO = BigInteger.ZERO
    private val POLY = ONE.shiftLeft(DEGREE).or(BigInteger.valueOf(0xC9)) // x^163 + x^7 + x^6 + x^3 + 1
    private val B = BigInteger("20a601907b8c953ca1481eb10512f78744a3205fd", 16)
    private val GX = BigInteger("3f0eba16286a2d57ea0991168d4994637e8343e36", 16)
    private val GY = BigInteger("0d51fbc6c71a0094fa2cdd545b11c5c0c797324f1", 16)
    private val ORDER = BigInteger("40000000000000000000292fe77e70c12a4234c33", 16)

    // как в tiny-ECDH: обнулить биты >= 162, чтобы скаляр был меньше порядка группы
    private val SCALAR_MASK = ONE.shiftLeft(ORDER.bitLength() - 1).subtract(ONE)

    private data class Point(val x: BigInteger, val y: BigInteger) {
        val isZero get() = x.signum() == 0 && y.signum() == 0
    }

    private val INFINITY = Point(ZERO, ZERO)

    private fun mul(a0: BigInteger, b0: BigInteger): BigInteger {
        var a = a0
        var b = b0
        var result = ZERO
        while (b.signum() != 0) {
            if (b.testBit(0)) result = result.xor(a)
            b = b.shiftRight(1)
            a = a.shiftLeft(1)
            if (a.testBit(DEGREE)) a = a.xor(POLY)
        }
        return result
    }

    // расширенный алгоритм Евклида для многочленов над GF(2)
    private fun inv(a: BigInteger): BigInteger {
        var u = a
        var v = POLY
        var g1 = ONE
        var g2 = ZERO
        while (u != ONE) {
            var j = u.bitLength() - v.bitLength()
            if (j < 0) {
                u = v.also { v = u }
                g1 = g2.also { g2 = g1 }
                j = -j
            }
            u = u.xor(v.shiftLeft(j))
            g1 = g1.xor(g2.shiftLeft(j))
        }
        return g1
    }

    private fun onCurve(p: Point): Boolean {
        val x2 = mul(p.x, p.x)
        return mul(p.y, p.y).xor(mul(p.x, p.y)) == mul(x2, p.x).xor(x2).xor(B)
    }

    private fun double(p: Point): Point {
        if (p.x.signum() == 0) return INFINITY
        val l = mul(inv(p.x), p.y).xor(p.x)
        val x3 = mul(l, l).xor(l).xor(ONE)
        val y3 = mul(p.x, p.x).xor(mul(l.xor(ONE), x3))
        return Point(x3, y3)
    }

    private fun add(p: Point, q: Point): Point {
        if (q.isZero) return p
        if (p.isZero) return q
        if (p.x == q.x) return if (p.y == q.y) double(p) else INFINITY
        val l = mul(inv(p.x.xor(q.x)), p.y.xor(q.y))
        val x3 = mul(l, l).xor(l).xor(p.x).xor(q.x).xor(ONE)
        val y3 = mul(l, p.x.xor(x3)).xor(x3).xor(p.y)
        return Point(x3, y3)
    }

    private fun multiply(p: Point, k: BigInteger): Point {
        var r = INFINITY
        for (i in k.bitLength() - 1 downTo 0) {
            r = double(r)
            if (k.testBit(i)) r = add(r, p)
        }
        return r
    }

    private fun scalar(privateKey: ByteArray) = leToInt(privateKey).and(SCALAR_MASK)

    private fun encode(p: Point) = intToLe(p.x, KEY_BYTES) + intToLe(p.y, KEY_BYTES)

    fun generatePrivateKey(random: SecureRandom = SecureRandom()): ByteArray {
        while (true) {
            val key = ByteArray(KEY_BYTES).also(random::nextBytes)
            if (leToInt(key).bitLength() >= DEGREE / 2) return key
        }
    }

    fun publicKey(privateKey: ByteArray): ByteArray = encode(multiply(Point(GX, GY), scalar(privateKey)))

    fun sharedSecret(privateKey: ByteArray, remotePublic: ByteArray): ByteArray {
        val p = Point(
            leToInt(remotePublic.copyOfRange(0, KEY_BYTES)),
            leToInt(remotePublic.copyOfRange(KEY_BYTES, 2 * KEY_BYTES)),
        )
        require(!p.isZero && onCurve(p)) { "публичный ключ браслета не лежит на кривой B-163" }
        return encode(multiply(p, scalar(privateKey)))
    }

    private fun leToInt(bytes: ByteArray) = BigInteger(1, bytes.reversedArray())

    private fun intToLe(value: BigInteger, size: Int): ByteArray {
        val be = value.toByteArray()
        return ByteArray(size) { i -> if (i < be.size) be[be.size - 1 - i] else 0 }
    }
}

fun aesEcb(data: ByteArray, key: ByteArray): ByteArray =
    Cipher.getInstance("AES/ECB/NoPadding").run {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        doFinal(data)
    }

const val ENDPOINT_AUTH = 0x0082
private const val MIN_MTU = 23

/** Разбивает сообщение на куски для характеристики 0x0016 (без шифрования — для авторизации хватает). */
class ChunkedEncoder(mtu: Int = MIN_MTU) {
    private val maxChunk = minOf(512, maxOf(MIN_MTU, mtu) - 3)
    private var handle = 0

    fun encode(endpoint: Int, data: ByteArray): List<ByteArray> {
        handle = (handle + 1) and 0xFF
        val chunks = mutableListOf<ByteArray>()
        var offset = 0
        var count = 0
        while (offset < data.size || count == 0) {
            val first = count == 0
            val headerSize = if (first) 11 else 5
            val end = minOf(data.size, offset + maxChunk - headerSize)
            val part = data.copyOfRange(offset, end)
            offset = end
            var flags = if (first) 0x01 else 0x00
            if (offset >= data.size) flags = flags or 0x02 or 0x04 // последний кусок, нужен ack
            var header = byteArrayOf(0x03, flags.toByte(), 0x00, handle.toByte(), count.toByte())
            if (first) header += le32(data.size) + byteArrayOf(endpoint.toByte(), (endpoint shr 8).toByte())
            chunks += header + part
            count++
        }
        return chunks
    }
}

/**
 * Собирает сообщение из кусков характеристики 0x0017. Зашифрованные сообщения не расшифровываются,
 * но подтверждение (ack) для них формируется — иначе браслет будет повторять отправку.
 */
class ChunkedDecoder {
    class Message(val endpoint: Int, val payload: ByteArray, val encrypted: Boolean, val ack: ByteArray?)

    private val buffer = ByteArrayOutputStream()
    private var length = 0
    private var endpoint = 0

    fun feed(data: ByteArray): Message? {
        if (data.size < 5 || data[0] != 0x03.toByte()) return null
        val flags = data[1].toInt() and 0xFF
        val handle = data[3]
        val count = data[4]
        val encrypted = flags and 0x08 != 0
        var bodyStart = 5
        if (flags and 0x01 != 0) {
            if (data.size < 11) return null
            length = le32(data, 5)
            endpoint = le16(data, 9)
            buffer.reset()
            bodyStart = 11
        }
        buffer.write(data, bodyStart, data.size - bodyStart)
        if (flags and 0x02 == 0) return null
        val ack = if (flags and 0x04 != 0) byteArrayOf(0x04, 0x00, handle, 0x01, count) else null
        val all = buffer.toByteArray()
        val payload = if (encrypted) all else all.copyOf(minOf(length, all.size))
        return Message(endpoint, payload, encrypted, ack)
    }
}

/** Состояние авторизации: формирует команды и отвечает на запрос браслета. */
class Auth2021(private val authKey: ByteArray, privateKey: ByteArray? = null) {
    private val privateKey = privateKey ?: EcdhB163.generatePrivateKey()
    val publicKey: ByteArray = EcdhB163.publicKey(this.privateKey)

    fun pubkeyCommand(): ByteArray = byteArrayOf(0x04, 0x02, 0x00, 0x02) + publicKey

    /** Ответ на 10 04 01 <random 16> <pubkey 48>. */
    fun answerChallenge(payload: ByteArray): ByteArray {
        val remoteRandom = payload.copyOfRange(3, 19)
        val shared = EcdhB163.sharedSecret(privateKey, payload.copyOfRange(19, 67))
        val sessionKey = ByteArray(16) { (shared[it + 8].toInt() xor authKey[it].toInt()).toByte() }
        return byteArrayOf(0x05) + aesEcb(remoteRandom, authKey) + aesEcb(remoteRandom, sessionKey)
    }
}

fun le16(data: ByteArray, offset: Int): Int =
    (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

fun le32(data: ByteArray, offset: Int): Int =
    le16(data, offset) or (le16(data, offset + 2) shl 16)

fun le32(value: Int): ByteArray = ByteArray(4) { (value shr (8 * it)).toByte() }

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
