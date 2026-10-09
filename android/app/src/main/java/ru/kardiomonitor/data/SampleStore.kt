package ru.kardiomonitor.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import ru.kardiomonitor.ble.ActivitySample

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

    fun insert(samples: List<ActivitySample>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues()
            for (s in samples) {
                values.clear()
                values.put("ts", s.epochSecond)
                if (s.heartRate != null) values.put("hr", s.heartRate) else values.putNull("hr")
                values.put("steps", s.steps)
                values.put("kind", s.kind)
                values.put("intensity", s.intensity)
                db.insertWithOnConflict("samples", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Записи в полуинтервале [from, to), epoch seconds. */
    fun query(from: Long, to: Long): List<ActivitySample> =
        readableDatabase.rawQuery(
            "SELECT ts, hr, steps, kind, intensity FROM samples WHERE ts >= ? AND ts < ? ORDER BY ts",
            arrayOf(from.toString(), to.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        ActivitySample(
                            epochSecond = c.getLong(0),
                            heartRate = if (c.isNull(1)) null else c.getInt(1),
                            steps = c.getInt(2),
                            kind = c.getInt(3),
                            intensity = c.getInt(4),
                        )
                    )
                }
            }
        }

    fun lastTimestamp(): Long? =
        readableDatabase.rawQuery("SELECT MAX(ts) FROM samples", null).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
}
