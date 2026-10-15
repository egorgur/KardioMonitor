package ru.kardiomonitor.features.monitoring.fakes

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import ru.kardiomonitor.features.monitoring.domain.ActivityHistory
import ru.kardiomonitor.features.monitoring.domain.ActivityMinute
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** История в памяти: одна запись на минуту, как в базе. Дни — по UTC. */
class FakeActivityHistory(initial: List<ActivityMinute> = emptyList()) : ActivityHistory {
    private val byStart = sortedMapOf<Instant, ActivityMinute>().apply { initial.forEach { put(it.start, it) } }
    private val changeEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    val minutes: List<ActivityMinute> get() = byStart.values.toList()

    override val changes: Flow<Unit> = changeEvents

    override suspend fun save(minutes: List<ActivityMinute>) {
        minutes.forEach { byStart[it.start] = it }
        if (minutes.isNotEmpty()) changeEvents.tryEmit(Unit)
    }

    override suspend fun minutesOf(day: LocalDate): List<ActivityMinute> =
        byStart.values.filter { it.start.atZone(ZoneOffset.UTC).toLocalDate() == day }

    override suspend fun lastRecordedMinute(): Instant? = byStart.keys.lastOrNull()
}
