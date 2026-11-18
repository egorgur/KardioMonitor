package ru.kardiomonitor.bandprotocol.xiaomi

import java.security.MessageDigest
import java.util.zip.CRC32

/** Один общий бюджет включает посторонние файлы, оба чтения и retention. */
internal class XiaomiHistoryTransfer(
    private val repeat: Boolean,
    private val reference: XiaomiHistoryReference? = null,
    private val sample: XiaomiHistoryReferenceSample? = null,
) : AutoCloseable {
    private var closed = false
    private var buffer = ByteArray(MAX_BYTES)
    private var size = 0
    private var totalChunks = 0
    private var chunks = 0
    private var startedAfterRequest = false
    private var target: ByteArray? = null
    private var baselineDigest: ByteArray? = null
    var aggregate: XiaomiHistoryAggregate? = null
        private set
    var bytesIdentical: Boolean? = null
        private set
    var aggregatesConsistent: Boolean? = null
        private set
    var referenceMatches: Boolean? = null
        private set
    var referenceComparison: XiaomiHistoryReferenceComparison? = null
        private set
    var exactTransfers: Int = 0
        private set
    var completeFiles: Int = 0
        private set
    var payloadBytes: Int = 0
        private set
    private var receivedMessages = 0
    private var waitingMessages = 0
    private var desiredTransfers = 0
    val found: Boolean get() = desiredTransfers > 0 && exactTransfers == desiredTransfers

    fun request(identifier: ByteArray) {
        if (closed || target != null || identifier.size != 7 || identifier[5] != 4.toByte() || identifier[6] != 0.toByte()) XiaomiProto.invalid()
        target = identifier.copyOf()
        desiredTransfers = 1
    }
    fun repeatRequest() {
        requireBoundary()
        if (!repeat || exactTransfers != 1 || desiredTransfers != 1 || baselineDigest == null || completeFiles >= 16)
            XiaomiProto.invalid()
        desiredTransfers = 2
    }
    fun accept(payload: ByteArray, waitingForIndex: Boolean) {
        if (closed || ++receivedMessages > 4096 || waitingForIndex && ++waitingMessages > 32) XiaomiProto.invalid()
        payloadBytes += payload.size
        if (payloadBytes > MAX_BYTES || payload.size <= 4) XiaomiProto.invalid()
        val declared = XiaomiWire.le16(payload, 0)
        val number = XiaomiWire.le16(payload, 2)
        if (declared !in 1..2048 || number != chunks + 1 || number > declared || totalChunks != 0 && declared != totalChunks)
            XiaomiProto.invalid()
        if (chunks == 0) {
            if (completeFiles >= 16) XiaomiProto.invalid()
            startedAfterRequest = desiredTransfers > exactTransfers
        }
        if (payload.size - 4 > buffer.size - size) XiaomiProto.invalid()
        payload.copyInto(buffer, size, 4)
        size += payload.size - 4
        totalChunks = declared
        chunks++
        if (chunks == totalChunks) complete()
    }
    private fun complete() {
        try {
            if (size < 13 || buffer[7] != 0.toByte()) XiaomiProto.invalid()
            val crc = CRC32().apply { update(buffer, 0, size - 4) }.value
            val expected = (0..3).fold(0L) { value, index -> value or (XiaomiWire.u(buffer[size - 4 + index]).toLong() shl (index * 8)) }
            if (crc != expected) XiaomiProto.invalid()
            val identifier = target
            if (startedAfterRequest && desiredTransfers > exactTransfers && identifier != null &&
                XiaomiHistorySnapshot.matches(identifier, buffer, 0)) verifyExact()
            completeFiles++
        } finally {
            buffer.fill(0, 0, size)
            size = 0; chunks = 0; totalChunks = 0
        }
    }
    private fun verifyExact() {
        val parsed = XiaomiDailyDetails.parse(buffer, size)
        sample?.acceptExact(buffer, size)
        reference?.let {
            val comparison = XiaomiHistoryReferenceCheck.compare(buffer, size, it)
            referenceMatches = referenceMatches != false && comparison.matches
            referenceComparison = referenceComparison?.combine(comparison) ?: comparison
        }
        val digest = if (repeat) MessageDigest.getInstance("SHA-256").run { update(buffer, 0, size); digest() } else null
        if (exactTransfers == 0) {
            aggregate = parsed
            baselineDigest = digest
        } else {
            try {
                bytesIdentical = baselineDigest?.let { MessageDigest.isEqual(it, digest) } == true
                aggregatesConsistent = aggregate == parsed
            } finally { digest?.fill(0); baselineDigest?.fill(0); baselineDigest = null }
        }
        exactTransfers++
    }
    fun requireBoundary() { if (closed || chunks != 0) XiaomiProto.invalid() }
    override fun close() {
        closed = true
        buffer.fill(0); target?.fill(0); baselineDigest?.fill(0)
        buffer = ByteArray(0); target = null; baselineDigest = null
    }
    override fun toString(): String = "XiaomiHistoryTransfer"
    companion object { const val MAX_BYTES = 512 * 1024 }
}
