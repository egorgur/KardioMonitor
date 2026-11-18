package ru.kardiomonitor.bandprotocol.xiaomi

/** Чтение одного явно выбранного файла; никакого автообхода архива или ACK 8/5. */
internal object XiaomiHistoryRead {
    fun read(
        offset: Int,
        repeat: Boolean,
        transfer: XiaomiHistoryTransfer,
        query: () -> XiaomiProto,
        select: ((List<ByteArray>) -> Int)? = null,
        diagnostics: XiaomiHistoryDiagnostics? = null,
        source: XiaomiHistoryIndex = XiaomiHistoryIndex.PAST,
        fetch: (ByteArray) -> Unit,
    ): XiaomiHistoryVerification {
        diagnostics?.stage = XiaomiHistoryDiagnostics.Stage.INDEX
        query().use { index ->
            XiaomiHistorySnapshot(index, source).use { before ->
                val details = before.details()
                try {
                    val identifier = selectIdentifier(details, offset, select, diagnostics)
                    transfer.request(identifier)
                    diagnostics?.stage = XiaomiHistoryDiagnostics.Stage.FIRST_TRANSFER
                    fetch(identifier)
                    if (repeat) {
                        transfer.repeatRequest()
                        diagnostics?.stage = XiaomiHistoryDiagnostics.Stage.REPEAT_TRANSFER
                        fetch(identifier)
                    }
                    diagnostics?.stage = XiaomiHistoryDiagnostics.Stage.RETENTION
                    query().use { afterIndex ->
                        XiaomiHistorySnapshot(afterIndex, source).use { after ->
                            diagnostics?.stage = XiaomiHistoryDiagnostics.Stage.BOUNDARY
                            transfer.requireBoundary()
                            val result = result(details.size, transfer, before, after)
                            diagnostics?.stage = XiaomiHistoryDiagnostics.Stage.COMPLETE
                            return result
                        }
                    }
                } finally { details.forEach { it.fill(0) } }
            }
        }
    }
    private fun selectIdentifier(
        details: List<ByteArray>,
        offset: Int,
        select: ((List<ByteArray>) -> Int)?,
        diagnostics: XiaomiHistoryDiagnostics?,
    ): ByteArray {
        diagnostics?.stage = XiaomiHistoryDiagnostics.Stage.SELECTION
        val selected = select?.invoke(details) ?: offset
        if (selected !in details.indices) XiaomiProto.invalid()
        return details[selected]
    }
    private fun result(
        candidates: Int,
        transfer: XiaomiHistoryTransfer,
        before: XiaomiHistorySnapshot,
        after: XiaomiHistorySnapshot,
    ): XiaomiHistoryVerification = XiaomiHistoryVerification(
        candidateFiles = candidates,
        omittedFiles = candidates - 1,
        exactTransfers = transfer.exactTransfers,
        aggregate = transfer.aggregate ?: XiaomiProto.invalid(),
        retentionUnchanged = before.unchanged(after),
        repeatedBytesIdentical = transfer.bytesIdentical,
        repeatedAggregatesConsistent = transfer.aggregatesConsistent,
        referenceMatched = transfer.referenceMatches,
        referenceComparison = transfer.referenceComparison,
    )
}
