package ru.kardiomonitor.bandprotocol.xiaomi

import java.security.MessageDigest

/** Opt-in образец двух exact чтений только в RAM; не журнал и не clinical history. */
internal class XiaomiHistoryReferenceSample : AutoCloseable {
    enum class WindowRelation { BEFORE, INSIDE, AFTER, EMPTY, UNSUPPORTED_TIME }
    private var bytes = ByteArray(0)
    private var reads = 0
    private var closed = false
    val verified: Boolean get() = !closed && reads == 2 && bytes.isNotEmpty()
    val cleared: Boolean get() = closed && bytes.isEmpty() && reads == 0

    fun acceptExact(source: ByteArray, size: Int) {
        if (closed || reads >= 2 || size !in 18..minOf(source.size, XiaomiHistoryTransfer.MAX_BYTES)) XiaomiProto.invalid()
        if (reads == 0) bytes = source.copyOf(size)
        else {
            val repeated = source.copyOf(size)
            try { if (!MessageDigest.isEqual(bytes, repeated)) XiaomiProto.invalid() }
            finally { repeated.fill(0) }
        }
        reads++
    }
    fun compare(reference: XiaomiHistoryReference): XiaomiHistoryReferenceComparison {
        if (!verified) XiaomiProto.invalid()
        return XiaomiHistoryReferenceCheck.compare(bytes, bytes.size, reference)
    }
    fun windowRelation(reference: XiaomiHistoryReference): WindowRelation {
        if (!verified) XiaomiProto.invalid()
        val start = (0..3).fold(0L) { value, byte -> value or (XiaomiWire.u(bytes[byte]).toLong() shl (8 * byte)) }
        if (start > Int.MAX_VALUE || start % 60 != 0L) return WindowRelation.UNSUPPORTED_TIME
        val records = XiaomiDailyDetails.parseRecords(bytes, bytes.size).size
        if (records == 0) return WindowRelation.EMPTY
        if (start + (records - 1) * 60L > Int.MAX_VALUE) return WindowRelation.UNSUPPORTED_TIME
        return when {
            reference.epochSeconds < start -> WindowRelation.BEFORE
            reference.epochSeconds >= start + records * 60L -> WindowRelation.AFTER
            else -> WindowRelation.INSIDE
        }
    }
    override fun close() {
        bytes.fill(0)
        bytes = ByteArray(0)
        reads = 0
        closed = true
    }
    override fun toString(): String = "XiaomiHistoryReferenceSample"
}
