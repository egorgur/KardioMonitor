package ru.kardiomonitor.features.reminders.data

import org.json.JSONArray
import org.json.JSONObject
import ru.kardiomonitor.features.reminders.domain.ReminderDayFacts
import ru.kardiomonitor.features.reminders.domain.ReminderKind
import java.time.Instant
import java.time.LocalDate

internal object ReminderFactsJson {
    fun encode(facts: ReminderDayFacts): JSONObject = JSONObject()
        .put("day", facts.day.toString())
        .put("is_muted", facts.isMuted)
        .put("shown", JSONObject().also { shown ->
            facts.shown.forEach { (kind, times) -> shown.put(kind.name, JSONArray(times.map(Instant::toString))) }
        })
        .put("snoozed_until", JSONObject().also { snoozed ->
            facts.snoozedUntil.forEach { (kind, time) -> snoozed.put(kind.name, time.toString()) }
        })

    fun decode(json: JSONObject): ReminderDayFacts = ReminderDayFacts(
        day = LocalDate.parse(json.getString("day")),
        shown = decodeShown(json.getJSONObject("shown")),
        snoozedUntil = decodeSnoozed(json.getJSONObject("snoozed_until")),
        isMuted = json.getBoolean("is_muted"),
    )

    private fun decodeShown(json: JSONObject): Map<ReminderKind, List<Instant>> = json.keys().asSequence().associate { key ->
        val values = json.getJSONArray(key)
        ReminderKind.valueOf(key) to List(values.length()) { Instant.parse(values.getString(it)) }
    }

    private fun decodeSnoozed(json: JSONObject): Map<ReminderKind, Instant> = json.keys().asSequence().associate { key ->
        ReminderKind.valueOf(key) to Instant.parse(json.getString(key))
    }
}
