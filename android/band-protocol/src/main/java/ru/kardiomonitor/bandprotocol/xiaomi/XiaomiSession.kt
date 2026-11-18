package ru.kardiomonitor.bandprotocol.xiaomi

import java.io.InputStream
import java.io.OutputStream
import java.util.ArrayDeque

/** Ограниченный read-only subset; history verification не разрешает clinical import. Нет init/sync/retry/live/write.
 * Источник wire/schema: docs/band9-implementation-context.md §6, оригинальный Session.java.
 */
internal class XiaomiSession(private val input: InputStream, private val output: OutputStream) : AutoCloseable {
    private val auth = XiaomiAuth()
    private val pending = ArrayDeque<Int>()
    private var serial = 0
    private var expectedReceive = 0
    private var hasReceived = false
    private var started = false
    private var receivedFrames = 0
    private var ordinaryFrames = 0
    private var authenticated = false
    private var verified = false
    private var historyTransfer: XiaomiHistoryTransfer? = null
    private var historyWaitingIndex = false
    private var historySource: XiaomiHistoryIndex? = null

    fun open(key: ByteArray, api: Int, phase: (XiaomiOpenPhase) -> Unit = {}) {
        if (started) XiaomiProto.invalid()
        started = true
        phase(XiaomiOpenPhase.VERSION)
        write(XiaomiWire.versionRequest())
        if (XiaomiWire.version(input) != 2) XiaomiProto.invalid()
        phase(XiaomiOpenPhase.SESSION)
        startSession()
        phase(XiaomiOpenPhase.AUTH_CHALLENGE)
        send(auth.nonceCommand(), handshake = true)
        receive().use {
            phase(XiaomiOpenPhase.WATCH_PROOF)
            val response = auth.respond(key, it, api)
            phase(XiaomiOpenPhase.AUTH_CONFIRM)
            send(response, handshake = true)
        }
        receive().use { auth.confirm(it) }
        authenticated = true
        phase(XiaomiOpenPhase.DEVICE_INFO)
        query(2, 2).use { command ->
            command.message(4).use { system ->
                system.message(3).use {
                    phase(XiaomiOpenPhase.IDENTITY_GUARD)
                    if (it.text(4) != "M2345B1" || it.text(2) != "2.3.98") XiaomiProto.invalid()
                }
            }
        }
        verified = true
    }
    private fun startSession() {
        write(XiaomiWire.sessionStart())
        repeat(8) {
            read().use { frame ->
                if (frame.type == 2) { XiaomiWire.validateSession(frame.payload); return }
                if (frame.type != 1) XiaomiProto.invalid()
            }
        }
        XiaomiProto.invalid()
    }
    fun battery(): Int? {
        if (!verified) XiaomiProto.invalid()
        query(2, 1).use { command ->
            if (!command.has(4)) return null
            command.message(4).use { system ->
                if (!system.has(2)) return null
                system.message(2).use { power ->
                    if (!power.has(1)) return null
                    power.message(1).use { battery ->
                        if (!battery.has(1)) return null
                        val level = battery.number(1)
                        if (level !in 0..100) XiaomiProto.invalid()
                        return level.toInt()
                    }
                }
            }
        }
    }
    fun heartRateSettings(): XiaomiHeartRateSettings {
        if (!verified) XiaomiProto.invalid()
        query(8, 10).use { command ->
            if (!command.has(10)) return XiaomiHeartRateSettings(null, null)
            command.message(10).use { health ->
                if (!health.has(8)) return XiaomiHeartRateSettings(null, null)
                health.message(8).use { settings -> return settings(settings) }
            }
        }
    }
    fun verifyPastHistory(
        offset: Int,
        repeat: Boolean,
        reference: XiaomiHistoryReference? = null,
        selectReferenceTime: Boolean = false,
        diagnostics: XiaomiHistoryDiagnostics? = null,
        source: XiaomiHistoryIndex = XiaomiHistoryIndex.PAST,
        sample: XiaomiHistoryReferenceSample? = null,
        latestTarget: XiaomiHistoryLatestTarget? = null,
    ): XiaomiHistoryVerification {
        if (!verified || offset !in 0..255 || historyTransfer != null ||
            selectReferenceTime && reference == null ||
            source == XiaomiHistoryIndex.TODAY && reference == null && sample == null ||
            sample != null && (!repeat || reference != null || selectReferenceTime) ||
            latestTarget != null && sample == null) XiaomiProto.invalid()
        XiaomiHistoryTransfer(repeat, reference, sample).use { transfer ->
            historyTransfer = transfer
            historySource = source
            try {
                val selector: ((List<ByteArray>) -> Int)? = if (selectReferenceTime) { details ->
                    XiaomiHistoryReferenceTarget.select(details, requireNotNull(reference), diagnostics)
                } else latestTarget?.let { target -> { details -> target.select(details) } }
                return XiaomiHistoryRead.read(offset, repeat, transfer, { historyIndex(source) }, selector, diagnostics, source) { identifier ->
                    send(XiaomiHistorySnapshot.request(identifier), handshake = false)
                    while (!transfer.found) receivePacket()?.use { it.success() }
                }
            } finally { historyTransfer = null; historyWaitingIndex = false; historySource = null }
        }
    }
    private fun historyIndex(source: XiaomiHistoryIndex): XiaomiProto {
        historyWaitingIndex = true
        try { return query(8, source.subtype, source.request()) } finally { historyWaitingIndex = false }
    }
    private fun settings(settings: XiaomiProto): XiaomiHeartRateSettings {
        val disabled = if (settings.has(1)) settings.number(1) else null
        val interval = if (settings.has(2)) settings.number(2) else null
        if (disabled != null && disabled !in 0..1 || interval != null && interval !in 0..Int.MAX_VALUE) XiaomiProto.invalid()
        return XiaomiHeartRateSettings(disabled?.let { it == 1L }, interval?.toInt())
    }
    private fun query(type: Int, subtype: Int, request: ByteArray = XiaomiProto.command(type, subtype)): XiaomiProto {
        send(request, handshake = false)
        repeat(32) {
            val command = receive()
            try {
                if (command.number(1) == type.toLong() && command.number(2) == subtype.toLong()) {
                    command.success()
                    return command
                }
            } catch (error: Exception) { command.close(); throw error }
            command.close()
        }
        XiaomiProto.invalid()
    }
    private fun send(command: ByteArray, handshake: Boolean) {
        var payload: ByteArray? = null
        var envelope: ByteArray? = null
        try {
            XiaomiProto.parse(command).use { permitted ->
                val historyCommand = historyTransfer != null && verified && !handshake && permitted.number(1) == 8L &&
                    (permitted.number(2) == 3L || permitted.number(2) == historySource?.subtype?.toLong())
                if (!historyCommand && !isAllowed(permitted.number(1), permitted.number(2), handshake)) XiaomiProto.invalid()
            }
            if (!handshake && !authenticated || pending.size >= 8) XiaomiProto.invalid()
            payload = if (handshake) command.copyOf() else auth.encrypt(command)
            envelope = byteArrayOf(1, if (handshake) 1 else 2) + payload
            val sequence = serial++ and 255
            write(XiaomiWire.frame(3, sequence, envelope))
            pending.addLast(sequence)
        } finally { command.fill(0); payload?.fill(0); envelope?.fill(0) }
    }
    private fun receive(): XiaomiProto {
        repeat(64) { receivePacket()?.let { return it } }
        XiaomiProto.invalid()
    }
    private fun receivePacket(): XiaomiProto? {
        read().use { frame ->
            if (frame.type == 1) { acknowledge(frame.sequence); return null }
            if (frame.type != 3 || frame.payload.size < 2) XiaomiProto.invalid()
            if (hasReceived && frame.sequence == ((expectedReceive - 1) and 255)) {
                write(XiaomiWire.frame(1, frame.sequence, ByteArray(0))); return null
            }
            if (frame.sequence != expectedReceive) XiaomiProto.invalid()
            expectedReceive = (expectedReceive + 1) and 255
            hasReceived = true
            write(XiaomiWire.frame(1, frame.sequence, ByteArray(0)))
            return decode(frame.payload)
        }
    }
    private fun decode(payload: ByteArray): XiaomiProto? {
        val channel = XiaomiWire.u(payload[0]) and 15
        if (channel != 1 && (channel != 5 || historyTransfer == null)) {
            if (historyTransfer != null) XiaomiProto.invalid()
            return null
        }
        val opcode = XiaomiWire.u(payload[1])
        if (opcode !in 1..2 || authenticated && opcode != 2) XiaomiProto.invalid()
        val encoded = payload.copyOfRange(2, payload.size)
        val decoded = try {
            if (opcode == 1) encoded.copyOf() else auth.decrypt(encoded, handshake = !authenticated)
        } finally { encoded.fill(0) }
        try {
            if (channel == 5) {
                requireNotNull(historyTransfer).accept(decoded, historyWaitingIndex)
                return null
            }
            return XiaomiProto.parse(decoded)
        } finally { decoded.fill(0) }
    }
    private fun acknowledge(sequence: Int) {
        if (!pending.contains(sequence)) return
        while (pending.isNotEmpty()) if (pending.removeFirst() == sequence) break
    }
    private fun read(): XiaomiWire.Frame {
        val frame = XiaomiWire.read(input)
        if (++receivedFrames > 8192 || historyTransfer == null && ++ordinaryFrames > 512) {
            frame.close(); XiaomiProto.invalid()
        }
        return frame
    }
    private fun write(frame: ByteArray) {
        try { output.write(frame); output.flush() } finally { frame.fill(0) }
    }
    override fun close() { auth.close(); pending.clear(); verified = false; authenticated = false; started = true }
    override fun toString(): String = "XiaomiSession"

    companion object {
        internal fun isAllowed(type: Long, subtype: Long, handshake: Boolean): Boolean =
            if (handshake) type == 1L && subtype in 26L..27L
            else type == 2L && subtype in 1L..2L || type == 8L && subtype == 10L
    }
}
