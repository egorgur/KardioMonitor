package ru.kardiomonitor.features.monitoring.fakes

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import ru.kardiomonitor.features.monitoring.domain.ActivityMinute
import ru.kardiomonitor.features.monitoring.domain.ActivitySource
import ru.kardiomonitor.features.monitoring.domain.HeartRate
import ru.kardiomonitor.features.monitoring.domain.StepCount
import java.time.ZonedDateTime

/** Браслет с записанной историей [recorded]: отдаёт минуты не раньше запрошенного начала. */
class FakeActivitySource(var recorded: List<ActivityMinute> = emptyList()) : ActivitySource {
    /** С какой минуты запрашивали историю. */
    val requestedStarts: MutableList<ZonedDateTime> = mutableListOf()

    override suspend fun minutesSince(
        start: ZonedDateTime,
        onProgress: (minutesReceived: Int) -> Unit,
    ): List<ActivityMinute> {
        requestedStarts += start
        val minutes = recorded.filter { !it.start.isBefore(start.toInstant()) }
        onProgress(minutes.size)
        return minutes
    }

    override fun liveHeartRate(): Flow<HeartRate?> = emptyFlow()

    override fun stepsToday(): Flow<StepCount> = emptyFlow()
}
