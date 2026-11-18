package ru.kardiomonitor.bandprotocol.xiaomi

/** Только фиксированные стадии и причины; нет исходных исключений, ID или времени. */
internal class XiaomiHistoryDiagnostics(private val clockEpochSeconds: Long? = null) {
    enum class Stage {
        INDEX, SELECTION, FIRST_TRANSFER, REPEAT_TRANSFER, RETENTION, BOUNDARY, COMPLETE,
    }
    enum class Selection {
        SELECTED, EMPTY, UNSUPPORTED_EPOCH, UNALIGNED_EPOCH, TIMEZONE_UNAVAILABLE,
        NO_PRECEDING_FILE, OUTSIDE_WINDOW, AMBIGUOUS,
    }
    @Volatile var stage = Stage.INDEX
    @Volatile var selection: Selection? = null
    @Volatile var selectionEvidence: XiaomiHistorySelectionEvidence? = null
        private set
    fun observe(starts: List<Long>, reference: XiaomiHistoryReference) {
        selectionEvidence = XiaomiHistorySelectionEvidence.observe(starts, reference, clockEpochSeconds)
    }
    override fun toString(): String = "XiaomiHistoryDiagnostics"
}
