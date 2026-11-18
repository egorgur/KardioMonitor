package ru.kardiomonitor.bandprotocol.xiaomi

/** Совпадение одной точки не сертифицирует время/значения архива или HR0/255. */
internal data class XiaomiHistoryReferenceComparison(
    val recordLocated: Boolean,
    val timezoneMatches: Boolean,
    val heartRateMatches: Boolean?,
    val stepsMatch: Boolean?,
) {
    val matches: Boolean get() = recordLocated && timezoneMatches &&
        (heartRateMatches == true || stepsMatch == true) && heartRateMatches != false && stepsMatch != false
    fun combine(other: XiaomiHistoryReferenceComparison): XiaomiHistoryReferenceComparison = XiaomiHistoryReferenceComparison(
        recordLocated = recordLocated && other.recordLocated,
        timezoneMatches = timezoneMatches && other.timezoneMatches,
        heartRateMatches = heartRateMatches?.let { it && other.heartRateMatches == true },
        stepsMatch = stepsMatch?.let { it && other.stepsMatch == true },
    )
    override fun toString(): String = "XiaomiHistoryReferenceComparison"
}
