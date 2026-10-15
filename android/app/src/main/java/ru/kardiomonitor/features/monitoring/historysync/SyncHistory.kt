package ru.kardiomonitor.features.monitoring.historysync

import android.util.Log
import kotlinx.coroutines.withTimeoutOrNull
import ru.kardiomonitor.features.band.api.BandConnection
import ru.kardiomonitor.features.monitoring.domain.ActivityHistory
import ru.kardiomonitor.features.monitoring.domain.ActivitySource
import ru.kardiomonitor.features.monitoring.domain.HistorySyncStart
import ru.kardiomonitor.features.monitoring.domain.LastSync
import ru.kardiomonitor.features.monitoring.domain.SyncSettings
import java.io.IOException
import java.time.Clock
import java.time.ZonedDateTime
import kotlin.time.Duration

/** Итог синхронизации. */
internal sealed interface SyncResult {
    val summary: String

    data class Downloaded(val minutes: Int) : SyncResult {
        override val summary: String get() = if (minutes == 0) "новых данных нет" else "загружено $minutes мин"
    }

    data class Failed(val reason: String) : SyncResult {
        override val summary: String get() = "ошибка: $reason"
    }
}

/**
 * Синхронизация истории: в сеансе связи выгрузить минуты с браслета начиная с
 * [HistorySyncStart] и сохранить в [ActivityHistory]. Итог записывается в [SyncSettings].
 * Вызывается кнопкой и [HistorySyncWorker].
 */
internal class SyncHistory(
    private val connection: BandConnection,
    private val source: ActivitySource,
    private val history: ActivityHistory,
    private val settings: SyncSettings,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    suspend operator fun invoke(
        isBackground: Boolean,
        timeout: Duration = Duration.INFINITE,
        onProgress: (minutesReceived: Int) -> Unit = {},
    ): SyncResult {
        val result = try {
            val minutes = withTimeoutOrNull(timeout) {
                connection.withSession(isBackground) { downloadNewMinutes(onProgress) }
            } ?: throw IOException("синхронизация не уложилась в $timeout")
            SyncResult.Downloaded(minutes)
        } catch (e: IOException) {
            Log.w(TAG, "синхронизация не удалась", e)
            SyncResult.Failed(e.message ?: e.toString())
        }
        Log.i(TAG, "синхронизация (${if (isBackground) "фон" else "экран"}): ${result.summary}")
        settings.recordSync(LastSync(clock.instant(), result.summary, isBackground))
        return result
    }

    private suspend fun downloadNewMinutes(onProgress: (Int) -> Unit): Int {
        val start = HistorySyncStart.after(history.lastRecordedMinute(), ZonedDateTime.now(clock))
        val minutes = source.minutesSince(start, onProgress)
        history.save(minutes)
        return minutes.size
    }

    private companion object {
        const val TAG = "KardioBLE"
    }
}
