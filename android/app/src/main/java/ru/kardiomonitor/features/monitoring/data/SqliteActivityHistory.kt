package ru.kardiomonitor.features.monitoring.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import ru.kardiomonitor.features.monitoring.domain.ActivityHistory
import ru.kardiomonitor.features.monitoring.domain.ActivityKind
import ru.kardiomonitor.features.monitoring.domain.ActivityMinute
import ru.kardiomonitor.features.monitoring.domain.HeartRate
import ru.kardiomonitor.features.monitoring.domain.Intensity
import ru.kardiomonitor.features.monitoring.domain.StepCount
import ru.kardiomonitor.platform.db.KardioDatabase
import ru.kardiomonitor.platform.db.KardioDatabase.Companion.ACTIVITY_TABLE
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** История активности в SQLite. Ключ — начало минуты (epoch seconds): повторная выгрузка перезаписывает. */
class SqliteActivityHistory(private val database: KardioDatabase) : ActivityHistory {
    private val changeEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    override val changes: Flow<Unit> = changeEvents.asSharedFlow()

    override suspend fun save(minutes: List<ActivityMinute>) {
        if (minutes.isEmpty()) return
        withContext(Dispatchers.IO) { insert(minutes) }
        changeEvents.tryEmit(Unit)
    }

    override suspend fun minutesOf(day: LocalDate): List<ActivityMinute> = withContext(Dispatchers.IO) {
        val zone = ZoneId.systemDefault()
        val from = day.atStartOfDay(zone).toEpochSecond()
        val to = day.plusDays(1).atStartOfDay(zone).toEpochSecond()
        database.readableDatabase.rawQuery(
            "SELECT ts, hr, steps, kind, intensity FROM $ACTIVITY_TABLE WHERE ts >= ? AND ts < ? ORDER BY ts",
            arrayOf(from.toString(), to.toString()),
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.toActivityMinute()) }
        }
    }

    override suspend fun lastRecordedMinute(): Instant? = withContext(Dispatchers.IO) {
        database.readableDatabase.rawQuery("SELECT MAX(ts) FROM $ACTIVITY_TABLE", null).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) Instant.ofEpochSecond(c.getLong(0)) else null
        }
    }

    private fun insert(minutes: List<ActivityMinute>) {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues()
            for (minute in minutes) {
                values.clear()
                values.put("ts", minute.start.epochSecond)
                val heartRate = minute.heartRate
                if (heartRate != null) values.put("hr", heartRate.bpm) else values.putNull("hr")
                values.put("steps", minute.steps.value)
                values.put("kind", kindCode(minute.kind))
                values.put("intensity", minute.intensity.value)
                db.insertWithOnConflict(ACTIVITY_TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun Cursor.toActivityMinute() = ActivityMinute(
        start = Instant.ofEpochSecond(getLong(0)),
        steps = StepCount(getInt(2)),
        heartRate = if (isNull(1)) null else HeartRate.fromMeasurement(getInt(1)),
        kind = kind(getInt(3)),
        intensity = Intensity(getInt(4)),
    )

    /** Тип активности хранится кодом браслета — так его записывал прототип (docs/ble-protocol.md). */
    private fun kindCode(kind: ActivityKind): Int = when (kind) {
        ActivityKind.NotWorn -> KIND_NOT_WORN
        ActivityKind.Charging -> KIND_CHARGING
        ActivityKind.Sleep -> KIND_SLEEP
        is ActivityKind.Other -> kind.code
    }

    private fun kind(code: Int): ActivityKind = when (code) {
        KIND_NOT_WORN -> ActivityKind.NotWorn
        KIND_CHARGING -> ActivityKind.Charging
        KIND_SLEEP -> ActivityKind.Sleep
        else -> ActivityKind.Other(code)
    }

    private companion object {
        const val KIND_NOT_WORN = 115
        const val KIND_CHARGING = 118
        const val KIND_SLEEP = 120
    }
}
