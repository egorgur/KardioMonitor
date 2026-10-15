package ru.kardiomonitor.platform.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * База приложения и миграции её схемы. Таблицами пользуются репозитории контекстов
 * (`features.*.data`). Имя файла `samples.db` осталось с прототипа: база уже есть на
 * телефоне пациента — её нельзя пересоздавать.
 */
class KardioDatabase(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE $ACTIVITY_TABLE (
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

    private fun createBatteryTable(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE $BATTERY_TABLE (ts INTEGER PRIMARY KEY, level INTEGER NOT NULL)")
    }

    companion object {
        private const val NAME = "samples.db"
        private const val VERSION = 2

        /** Минуты активности: ts — начало минуты (epoch seconds), kind — код браслета. */
        const val ACTIVITY_TABLE = "samples"

        /** Заряд браслета при каждом подключении. */
        const val BATTERY_TABLE = "battery"
    }
}
