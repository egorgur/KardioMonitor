package ru.kardiomonitor.features.monitoring.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import ru.kardiomonitor.features.monitoring.domain.ActivityHistory
import ru.kardiomonitor.features.monitoring.domain.ActivityMinute
import ru.kardiomonitor.features.monitoring.domain.HeartRate
import ru.kardiomonitor.features.monitoring.domain.Intensity
import ru.kardiomonitor.features.monitoring.domain.StepCount
import ru.kardiomonitor.platform.db.KardioDatabase
import ru.kardiomonitor.platform.db.KardioDatabase.Companion.ACTIVITY_TABLE
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** История активности в SQLite. Ключ — начало минуты (epoch seconds): повторная выгрузка перезаписывает. */
class SqliteActivityHistory(private val database: KardioDatabase) : ActivityHistory {
    private val changeEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    override val changes: Flow<Unit> = changeEvents.asSharedFlow()

    override suspend fun save(minutes: List<ActivityMinute>) {
        if (minutes.isEmpty()) return
        withDatabase { insert(minutes) }
        changeEvents.tryEmit(Unit)
    }

    override suspend fun minutesOf(day: LocalDate, timeZone: ZoneId): List<ActivityMinute> = withDatabase {
        val range = day.patientDayRange(timeZone)
        minutesIn(range.fromEpochSecond, range.toEpochSecond)
    }

    override suspend fun minutesBetween(
        firstDay: LocalDate,
        lastDay: LocalDate,
        timeZone: ZoneId,
    ): Map<LocalDate, List<ActivityMinute>> = withDatabase {
        require(!lastDay.isBefore(firstDay)) { "последний день диапазона раньше первого" }
        val first = firstDay.patientDayRange(timeZone)
        val last = lastDay.patientDayRange(timeZone)
        minutesIn(first.fromEpochSecond, last.toEpochSecond).groupBy { it.start.atZone(timeZone).toLocalDate() }
    }

    override suspend fun lastRecordedMinute(): Instant? = withDatabase {
        database.readableDatabase.rawQuery("SELECT MAX(ts) FROM $ACTIVITY_TABLE", null).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) Instant.ofEpochSecond(c.getLong(0)) else null
        }
    }

    override suspend fun minutesAfter(after: Instant?, limit: Int): List<ActivityMinute> = withDatabase {
        val from = after?.epochSecond ?: Long.MIN_VALUE
        database.readableDatabase.rawQuery(
            "SELECT ts, hr, steps, kind, intensity FROM $ACTIVITY_TABLE WHERE ts > ? ORDER BY ts LIMIT ?",
            arrayOf(from.toString(), limit.toString()),
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.toActivityMinute()) }
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
                values.put("kind", ActivityKindCodes.code(minute.kind))
                values.put("intensity", minute.intensity.value)
                db.insertWithOnConflict(ACTIVITY_TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun minutesIn(from: Long, to: Long): List<ActivityMinute> =
        database.readableDatabase.rawQuery(
            "SELECT ts, hr, steps, kind, intensity FROM $ACTIVITY_TABLE WHERE ts >= ? AND ts < ? ORDER BY ts",
            arrayOf(from.toString(), to.toString()),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toActivityMinute()) } }

    private fun Cursor.toActivityMinute() = ActivityMinute(
        start = Instant.ofEpochSecond(getLong(0)),
        steps = StepCount(getInt(2)),
        heartRate = if (isNull(1)) null else HeartRate.fromMeasurement(getInt(1)),
        kind = ActivityKindCodes.kind(getInt(3)),
        intensity = Intensity(getInt(4)),
    )

    /** Ошибка SQLite — сбой хранилища для вызывающего, а не падение процесса (coding-rules.md §7). */
    private suspend fun <T> withDatabase(block: () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: SQLiteException) {
            throw IOException("история активности недоступна: ${e.message}", e)
        }
    }
}
