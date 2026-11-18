package ru.kardiomonitor.bandprotocol.xiaomi

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Только синтетический peer; ключи/nonce не относятся к устройству. */
internal class XiaomiScriptedPeer : InputStream(), XiaomiTransport {
    val secret = ByteArray(16) { it.toByte() }
    private val watch = ByteArray(16) { (it + 32).toByte() }
    private var keys = ByteArray(0)
    private var sequence = 0
    private val queue = ArrayDeque<Int>()
    val commands = mutableListOf<Pair<Long, Long>>()
    var model = "M2345B1"
    var firmware = "2.3.98"
    var version = 2
    var malformedSession = false
    var badProof = false
    var rejectConfirmation = false
    var encryptedConfirmation = false
    var sequenceGap = false
    var nack = false
    var duplicate = false
    var cumulativeAcks = false
    var plaintextResponse = false
    var batteryAbsent = false
    var batteryValue = 73L
    var settingsBody = hex("0800100a")
    var settingsHealthAbsent = false
    var omitBatteryReply = false
    var historyEnabled = false
    var historyIndexSource = XiaomiHistoryIndex.PAST
    var historyScenario = "valid"
    var historyFetches = 0
    var historyIndices = 0
    val historyRequestedIds = mutableListOf<ByteArray>()
    var blockConnect = false
    val connecting = CountDownLatch(1)
    val reading = CountDownLatch(1)
    private val closed = CountDownLatch(1)
    var closeCount = 0
    var transportAcks = 0
    var companionVerified = false
    private var phone = ByteArray(0)
    override val input: InputStream get() = this
    override val output: OutputStream = object : OutputStream() {
        override fun write(value: Int) { throw AssertionError("Whole frame expected") }
        override fun write(bytes: ByteArray, offset: Int, length: Int) { handle(bytes.copyOfRange(offset, offset + length)) }
    }
    override fun connect() {
        connecting.countDown()
        if (blockConnect) {
            if (!closed.await(3, TimeUnit.SECONDS)) throw AssertionError("Connect was not closed")
            throw IOException("sensitive-address")
        }
    }
    override fun close() { closeCount++; closed.countDown() }
    override fun read(): Int {
        val bytes = ByteArray(1)
        return if (read(bytes, 0, 1) == -1) -1 else XiaomiWire.u(bytes[0])
    }
    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        if (queue.isEmpty()) {
            if (!omitBatteryReply) return -1
            reading.countDown()
            if (!closed.await(3, TimeUnit.SECONDS)) throw AssertionError("Read was not closed")
            throw IOException("sensitive-address")
        }
        val count = minOf(3, length, queue.size)
        repeat(count) { bytes[offset + it] = queue.removeFirst().toByte() }
        return count
    }
    private fun offer(frame: ByteArray) { frame.forEach { queue.addLast(XiaomiWire.u(it)) } }
    private fun receiveKey(): ByteArray = keys.copyOfRange(0, 16)
    private fun sendKey(): ByteArray = keys.copyOfRange(16, 32)
    private fun reply(command: ByteArray, encrypted: Boolean) {
        val body = if (encrypted) XiaomiCrypto.ctr(receiveKey(), command) else command
        val envelope = byteArrayOf(1, if (encrypted) 2 else 1) + body
        if (sequenceGap) { sequence++; sequenceGap = false }
        val frame = XiaomiWire.frame(3, sequence++ and 255, envelope)
        offer(frame)
        if (duplicate) offer(frame)
    }
    private fun handle(frameBytes: ByteArray) {
        if (frameBytes.contentEquals(XiaomiWire.versionRequest())) {
            val response = hex("badcfe00800600010000020061ef")
            response[10] = version.toByte()
            offer(response); return
        }
        XiaomiWire.read(ByteArrayInputStream(frameBytes)).use { frame ->
            if (frame.type == 2) {
                val response = frame.payload.copyOf(); response[0] = if (malformedSession) 0 else 2
                offer(XiaomiWire.frame(2, 0, response)); return
            }
            if (frame.type == 1) { transportAcks++; return }
            val encoded = frame.payload.copyOfRange(2, frame.payload.size)
            val decoded = if (frame.payload[1] == 2.toByte()) XiaomiCrypto.ctr(sendKey(), encoded) else encoded
            if (nack || !cumulativeAcks || frame.sequence and 1 == 0) {
                offer(XiaomiWire.frame(if (nack) 0 else 1, frame.sequence, ByteArray(0)))
            }
            if (nack) return
            XiaomiProto.parse(decoded).use { command ->
                val type = command.number(1); val subtype = command.number(2)
                commands.add(type to subtype)
                if (!XiaomiSession.isAllowed(type, subtype, handshake = type == 1L) &&
                    !(historyEnabled && type == 8L && (subtype == 3L || subtype == historyIndexSource.subtype.toLong()))) throw AssertionError("Unsafe command")
                when (type to subtype) {
                    1L to 26L -> challenge(command)
                    1L to 27L -> confirm(command)
                    2L to 2L -> device()
                    2L to 1L -> battery()
                    8L to 10L -> settings()
                    8L to historyIndexSource.subtype.toLong() -> historyIndex(command)
                    8L to 3L -> historyFile(command)
                    else -> throw AssertionError("Unsupported command")
                }
            }
        }
    }
    private fun challenge(command: XiaomiProto) {
        command.message(3).use { account -> account.message(30).use { phone = it.bytes(1).copyOf() } }
        keys = XiaomiCrypto.hkdf(phone + watch, secret)
        val proof = XiaomiCrypto.hmac(receiveKey(), watch + phone)
        if (badProof) proof[0] = (proof[0].toInt() xor 1).toByte()
        val verify = XiaomiProto.Writer().use { it.bytes(1, watch).bytes(2, proof).build() }
        val account = XiaomiProto.Writer().use { it.bytes(31, verify).build() }
        val response = XiaomiProto.Writer().use { it.number(1, 1).number(2, 26).bytes(3, account).build() }
        reply(response, encrypted = false)
    }
    private fun confirm(command: XiaomiProto) {
        command.message(3).use { account ->
            account.message(32).use { confirm ->
                if (!confirm.bytes(1).contentEquals(XiaomiCrypto.hmac(sendKey(), phone + watch))) throw AssertionError("Phone HMAC")
                if (confirm.bytes(2).size <= 4) throw AssertionError("Missing CCM info")
                companionVerified = true
            }
        }
        val response = XiaomiProto.Writer().use {
            it.number(1, 1).number(2, 27).number(100, if (rejectConfirmation) 1 else 0).build()
        }
        reply(response, encrypted = encryptedConfirmation)
    }
    private fun device() {
        val info = XiaomiProto.Writer().use { it.text(2, firmware).text(4, model).build() }
        val system = XiaomiProto.Writer().use { it.bytes(3, info).build() }
        reply(XiaomiProto.Writer().use { it.number(1, 2).number(2, 2).bytes(4, system).build() }, encrypted = !plaintextResponse)
    }
    private fun battery() {
        if (omitBatteryReply) return
        val level = XiaomiProto.Writer().use { it.number(1, batteryValue).build() }
        val power = XiaomiProto.Writer().use { it.bytes(1, level).build() }
        val system = XiaomiProto.Writer().use { it.bytes(2, power).build() }
        reply(XiaomiProto.Writer().use {
            it.number(1, 2).number(2, 1)
            if (!batteryAbsent) it.bytes(4, system)
            it.build()
        }, encrypted = true)
    }
    private fun settings() {
        val health = XiaomiProto.Writer().use { it.bytes(8, settingsBody).build() }
        reply(XiaomiProto.Writer().use {
            it.number(1, 8).number(2, 10)
            if (!settingsHealthAbsent) it.bytes(10, health)
            it.build()
        }, encrypted = true)
    }
    private fun historyIndex(command: XiaomiProto) {
        if (historyIndexSource == XiaomiHistoryIndex.TODAY) command.message(10).use { health ->
            health.message(5).use { options ->
                if (!options.has(1) || options.number(1) != 0L) throw AssertionError("Missing today GET options")
            }
        }
        historyIndices++
        val first = XiaomiHistoryFixtures.id
        val second = first.copyOf().also { it[0] = 42 }
        val ids = when {
            historyScenario == "invalid_index" -> ByteArray(8)
            historyScenario == "retention_changed" && historyIndices == 2 -> first
            historyScenario == "retention_duplicate" && historyIndices == 2 -> first + first
            historyIndices == 2 -> second + first
            else -> first + second
        }
        val health = XiaomiProto.Writer().use { it.bytes(historyIndexSource.field, ids).build() }
        if (historyIndices == 1 && historyScenario == "prestarted")
            historyChunk(XiaomiHistoryFixtures.chunk(XiaomiHistoryFixtures.file(), total = 2, number = 1, end = 15))
        if (historyIndices == 2 && historyScenario == "retention_partial")
            historyChunk(XiaomiHistoryFixtures.chunk(XiaomiHistoryFixtures.file(), total = 2, number = 1, end = 15))
        reply(XiaomiProto.Writer().use { it.number(1, 8).number(2, historyIndexSource.subtype.toLong()).bytes(10, health).build() }, encrypted = true)
    }
    private fun historyFile(command: XiaomiProto) {
        historyFetches++
        command.message(10).use { historyRequestedIds.add(it.bytes(2).copyOf()) }
        reply(XiaomiProto.command(8, 3), encrypted = true)
        if (historyScenario == "timeout") { omitBatteryReply = true; return }
        if (historyScenario == "prestarted" && historyFetches == 1)
            historyChunk(XiaomiHistoryFixtures.chunk(XiaomiHistoryFixtures.file(), total = 2, number = 2, start = 15))
        val unmatched = XiaomiHistoryFixtures.file(XiaomiHistoryFixtures.id.copyOf().also { it[0] = 99 })
        repeat(if (historyScenario == "missing") 17 else if (historyFetches == 1) 10 else 3) {
            historyChunk(XiaomiHistoryFixtures.chunk(unmatched))
        }
        var file = XiaomiHistoryFixtures.file()
        if (historyFetches == 2 && historyScenario == "changed") { file[16] = 71; XiaomiHistoryFixtures.crc(file) }
        if (historyScenario == "crc") file[file.lastIndex] = (file.last().toInt() xor 1).toByte()
        if (historyScenario == "padding") { file[7] = 1; XiaomiHistoryFixtures.crc(file) }
        if (historyScenario == "unsupported") file = XiaomiHistoryFixtures.file(body = byteArrayOf(1))
        if (historyScenario == "bytes") file = ByteArray(XiaomiHistoryTransfer.MAX_BYTES)
        if (file.size > 3000) for (offset in file.indices step 3000) {
            historyChunk(XiaomiHistoryFixtures.chunk(file, total = (file.size + 2999) / 3000,
                number = offset / 3000 + 1, start = offset, end = minOf(offset + 3000, file.size)))
        } else historyChunk(XiaomiHistoryFixtures.chunk(file), plaintext = historyScenario == "plaintext")
    }
    private fun historyChunk(chunk: ByteArray, plaintext: Boolean = false) {
        val body = if (plaintext) chunk else XiaomiCrypto.ctr(receiveKey(), chunk)
        offer(XiaomiWire.frame(3, sequence++ and 255, byteArrayOf(5, if (plaintext) 1 else 2) + body))
    }
    companion object {
        fun hex(text: String): ByteArray = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
