package ru.kardiomonitor.features.reminders.data

import org.json.JSONArray
import org.json.JSONObject
import ru.kardiomonitor.features.reminders.domain.DailyTargetReminderSettings
import ru.kardiomonitor.features.reminders.domain.IntervalReminderSettings
import ru.kardiomonitor.features.reminders.domain.MoveReminderSettings
import ru.kardiomonitor.features.reminders.domain.ReminderInterval
import ru.kardiomonitor.features.reminders.domain.ReminderSettings
import ru.kardiomonitor.features.reminders.domain.ReminderWindow
import java.time.DayOfWeek
import java.time.LocalTime

internal object ReminderSettingsJson {
    fun encode(settings: ReminderSettings): JSONObject = JSONObject()
        .put("move", interval(settings.move.schedule).put("steps_per_hour", settings.move.stepsPerHour))
        .put("daily_target", JSONObject()
            .put("enabled", settings.dailyTarget.enabled)
            .put("time", settings.dailyTarget.time.toString()))
        .put("wear_band", interval(settings.wearBand))

    fun decode(json: JSONObject): ReminderSettings {
        val move = json.getJSONObject("move")
        val daily = json.getJSONObject("daily_target")
        return ReminderSettings(
            move = MoveReminderSettings(schedule = interval(move), stepsPerHour = move.getInt("steps_per_hour")),
            dailyTarget = DailyTargetReminderSettings(daily.getBoolean("enabled"), LocalTime.parse(daily.getString("time"))),
            wearBand = interval(json.getJSONObject("wear_band")),
        )
    }

    private fun interval(settings: IntervalReminderSettings): JSONObject = JSONObject()
        .put("enabled", settings.enabled)
        .put("interval", settings.interval.name)
        .put("window_start", settings.window.start.toString())
        .put("window_end", settings.window.end.toString())
        .put("days", JSONArray(settings.days.map(DayOfWeek::name)))

    private fun interval(json: JSONObject): IntervalReminderSettings {
        val days = json.getJSONArray("days")
        return IntervalReminderSettings(
            enabled = json.getBoolean("enabled"),
            interval = ReminderInterval.valueOf(json.getString("interval")),
            window = ReminderWindow(
                LocalTime.parse(json.getString("window_start")),
                LocalTime.parse(json.getString("window_end")),
            ),
            days = List(days.length()) { DayOfWeek.valueOf(days.getString(it)) }.toSet(),
        )
    }
}
