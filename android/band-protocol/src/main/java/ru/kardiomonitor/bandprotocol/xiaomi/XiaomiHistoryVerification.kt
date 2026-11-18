package ru.kardiomonitor.bandprotocol.xiaomi

/** Ограниченное диагностическое чтение библиотеки, не успешная синхронизация истории.
 * Нет дат, ID, digest или отдельных значений. Клинический импорт и watermark запрещены.
 */
data class XiaomiHistoryVerification internal constructor(
    val candidateFiles: Int,
    val omittedFiles: Int,
    val exactTransfers: Int,
    val aggregate: XiaomiHistoryAggregate,
    val retentionUnchanged: Boolean,
    val repeatedBytesIdentical: Boolean?,
    val repeatedAggregatesConsistent: Boolean?,
    internal val referenceMatched: Boolean? = null,
    internal val referenceComparison: XiaomiHistoryReferenceComparison? = null,
) {
    val timeAndValuesVerified: Boolean get() = false
    val fullCoverageVerified: Boolean get() = false
    val clinicalImportAllowed: Boolean get() = false
    override fun toString(): String = "XiaomiHistoryVerification"
}
