package ru.kardiomonitor.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import ru.kardiomonitor.features.monitoring.domain.ActivityKind
import ru.kardiomonitor.features.monitoring.domain.ActivityMinute
import ru.kardiomonitor.features.monitoring.domain.HeartRate
import ru.kardiomonitor.features.monitoring.domain.Intensity
import ru.kardiomonitor.features.monitoring.domain.StepCount
import java.time.Instant

/** Поминутная история в SQLite. Ключ — время начала минуты (epoch seconds), повторная выгрузка перезаписывает. */
class SampleStore(context: Context) : SQLiteOpenHelper(context, "samples.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE samples (
                ts INTEGER PRIMARY KEY,
                hr INTEGER,
                steps INTEGER NOT NULL,
                kind INTEGER NOT NULL,
                intensity INTEGER NOT NULL
            )"""
        )
        createBatteryTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createBatteryTable(db)
    }

    /** Заряд браслета при каждом подключении — чтобы измерить реальный расход в сутки. */
    private fun createBatteryTable(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE battery (ts INTEGER PRIMARY KEY, level INTEGER NOT NULL)")
    }

    fun insertBattery(epochSecond: Long, level: Int) {
        val values = ContentValues().apply {
            put("ts", epochSecond)
            put("level", level)
        }
        writableDatabase.insertWithOnConflict("battery", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun insert(minutes: List<ActivityMinute>) {
        val db = writableDatabase
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
                db.insertWithOnConflict("samples", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Записи в полуинтервале [from, to), epoch seconds. */
    fun query(from: Long, to: Long): List<ActivityMinute> =
        readableDatabase.rawQuery(
            "SELECT ts, hr, steps, kind, intensity FROM samples WHERE ts >= ? AND ts < ? ORDER BY ts",
            arrayOf(from.toString(), to.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        ActivityMinute(
                            start = Instant.ofEpochSecond(c.getLong(0)),
                            steps = StepCount(c.getInt(2)),
                            heartRate = if (c.isNull(1)) null else HeartRate.fromMeasurement(c.getInt(1)),
                            kind = kind(c.getInt(3)),
                            intensity = Intensity(c.getInt(4)),
                        )
                    )
                }
            }
        }

    fun lastTimestamp(): Long? =
        readableDatabase.rawQuery("SELECT MAX(ts) FROM samples", null).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }

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
