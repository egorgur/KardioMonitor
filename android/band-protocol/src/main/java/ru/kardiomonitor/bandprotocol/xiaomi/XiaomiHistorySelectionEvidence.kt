package ru.kardiomonitor.bandprotocol.xiaomi

/** Только coarse relation; не сохраняет дескрипторы, время или показания. Не влияет на выбор. */
internal class XiaomiHistorySelectionEvidence private constructor(
    val referenceClockRelation: ReferenceClockRelation?,
    val candidateClockRelation: CandidateClockRelation?,
    val afterReferenceGap: AfterReferenceGap?,
    val afterReferenceSameLocalDay: Boolean?,
) {
    enum class ReferenceClockRelation { FUTURE, WITHIN_5M, WITHIN_1H, WITHIN_24H, OLDER }
    enum class CandidateClockRelation { ALL_NOT_FUTURE, SOME_FUTURE, ALL_FUTURE }
    enum class AfterReferenceGap { UNDER_5M, UNDER_1H, UNDER_24H, AT_LEAST_24H }
    override fun toString(): String = "XiaomiHistorySelectionEvidence"

    companion object {
        fun observe(starts: List<Long>, reference: XiaomiHistoryReference, clockEpochSeconds: Long?): XiaomiHistorySelectionEvidence {
            val clock = clockEpochSeconds?.takeIf { it in 0..Int.MAX_VALUE.toLong() }
            val first = starts.minOrNull()
            val after = first?.takeIf { it > reference.epochSeconds }
            return XiaomiHistorySelectionEvidence(
                referenceClockRelation = clock?.let { referenceClock(it - reference.epochSeconds) },
                candidateClockRelation = clock?.let { candidateClock(starts, it) },
                afterReferenceGap = after?.let { gap(it - reference.epochSeconds) },
                afterReferenceSameLocalDay = after?.let {
                    Math.floorDiv(it + reference.utcOffsetSeconds, 86_400L) ==
                        Math.floorDiv(reference.epochSeconds + reference.utcOffsetSeconds, 86_400L)
                },
            )
        }
        private fun referenceClock(age: Long): ReferenceClockRelation = when {
            age < 0 -> ReferenceClockRelation.FUTURE
            age < 300 -> ReferenceClockRelation.WITHIN_5M
            age < 3600 -> ReferenceClockRelation.WITHIN_1H
            age < 86_400 -> ReferenceClockRelation.WITHIN_24H
            else -> ReferenceClockRelation.OLDER
        }
        private fun candidateClock(starts: List<Long>, clock: Long): CandidateClockRelation? = when {
            starts.isEmpty() -> null
            starts.all { it <= clock } -> CandidateClockRelation.ALL_NOT_FUTURE
            starts.all { it > clock } -> CandidateClockRelation.ALL_FUTURE
            else -> CandidateClockRelation.SOME_FUTURE
        }
        private fun gap(delta: Long): AfterReferenceGap = when {
            delta < 300 -> AfterReferenceGap.UNDER_5M
            delta < 3600 -> AfterReferenceGap.UNDER_1H
            delta < 86_400 -> AfterReferenceGap.UNDER_24H
            else -> AfterReferenceGap.AT_LEAST_24H
        }
    }
}
