package ru.kardiomonitor.bandprotocol.xiaomi

/** Один кандидат из свежего индекса по гипотезе времени, не доказательство покрытия. */
internal object XiaomiHistoryReferenceTarget {
    fun select(
        details: List<ByteArray>,
        reference: XiaomiHistoryReference,
        diagnostics: XiaomiHistoryDiagnostics? = null,
    ): Int {
        diagnostics?.selection = null
        if (details.any { it.size != 7 || it[5] != 4.toByte() || it[6] != 0.toByte() }) XiaomiProto.invalid()
        diagnostics?.observe(emptyList(), reference)
        var candidates = remaining(details.indices.toList(), XiaomiHistoryDiagnostics.Selection.EMPTY, diagnostics)
        candidates = remaining(candidates.filter { start(details[it]) <= Int.MAX_VALUE },
            XiaomiHistoryDiagnostics.Selection.UNSUPPORTED_EPOCH, diagnostics)
        candidates = remaining(candidates.filter { start(details[it]) % 60 == 0L },
            XiaomiHistoryDiagnostics.Selection.UNALIGNED_EPOCH, diagnostics)
        candidates = remaining(candidates.filter { details[it][4].toInt() * 900 == reference.utcOffsetSeconds },
            XiaomiHistoryDiagnostics.Selection.TIMEZONE_UNAVAILABLE, diagnostics)
        diagnostics?.observe(candidates.map { start(details[it]) }, reference)
        candidates = remaining(candidates.filter { start(details[it]) <= reference.epochSeconds },
            XiaomiHistoryDiagnostics.Selection.NO_PRECEDING_FILE, diagnostics)
        candidates = remaining(candidates.filter { reference.epochSeconds - start(details[it]) < 2880L * 60 },
            XiaomiHistoryDiagnostics.Selection.OUTSIDE_WINDOW, diagnostics)
        val latest = candidates.maxOf { start(details[it]) }
        val selected = candidates.filter { start(details[it]) == latest }
        if (selected.size != 1) {
            diagnostics?.selection = XiaomiHistoryDiagnostics.Selection.AMBIGUOUS
            XiaomiProto.invalid()
        }
        diagnostics?.selection = XiaomiHistoryDiagnostics.Selection.SELECTED
        return selected.single()
    }
    private fun remaining(
        candidates: List<Int>,
        failure: XiaomiHistoryDiagnostics.Selection,
        diagnostics: XiaomiHistoryDiagnostics?,
    ): List<Int> {
        if (candidates.isEmpty()) {
            diagnostics?.selection = failure
            XiaomiProto.invalid()
        }
        return candidates
    }
    private fun start(id: ByteArray): Long = (0..3).fold(0L) { value, byte ->
        value or (XiaomiWire.u(id[byte]).toLong() shl (8 * byte))
    }
}
