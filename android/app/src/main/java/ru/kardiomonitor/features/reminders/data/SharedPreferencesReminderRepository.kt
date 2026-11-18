package ru.kardiomonitor.features.reminders.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import ru.kardiomonitor.features.reminders.domain.ReminderDayFacts
import ru.kardiomonitor.features.reminders.domain.ReminderKind
import ru.kardiomonitor.features.reminders.domain.ReminderRepository
import ru.kardiomonitor.features.reminders.domain.ReminderSettings
import java.io.IOException
import java.time.Instant
import java.time.LocalDate

/** Локальные настройки и факты напоминаний; на сервер не передаются. */
class SharedPreferencesReminderRepository(context: Context) : ReminderRepository {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val current = MutableStateFlow(loadSettings())

    override val settings: StateFlow<ReminderSettings> = current.asStateFlow()

    override fun save(settings: ReminderSettings) {
        persist(SETTINGS, ReminderSettingsJson.encode(settings))
        current.value = settings
    }

    override fun facts(day: LocalDate): ReminderDayFacts {
        val json = preferences.getString(FACTS, null) ?: return ReminderDayFacts(day)
        return try {
            ReminderFactsJson.decode(JSONObject(json)).takeIf { it.day == day } ?: ReminderDayFacts(day)
        } catch (e: Exception) {
            Log.w(TAG, "факты напоминаний повреждены — начат новый день", e)
            ReminderDayFacts(day)
        }
    }

    override fun recordShown(day: LocalDate, kind: ReminderKind, at: Instant) {
        val old = facts(day)
        val shown = old.shown + (kind to (old.shown[kind].orEmpty() + at))
        saveFacts(old.copy(shown = shown, snoozedUntil = old.snoozedUntil - kind))
    }

    override fun snooze(day: LocalDate, kind: ReminderKind, until: Instant) {
        val old = facts(day)
        saveFacts(old.copy(snoozedUntil = old.snoozedUntil + (kind to until)))
    }

    override fun mute(day: LocalDate) {
        saveFacts(facts(day).copy(isMuted = true))
    }

    private fun saveFacts(facts: ReminderDayFacts) {
        persist(FACTS, ReminderFactsJson.encode(facts))
    }

    private fun loadSettings(): ReminderSettings {
        val json = preferences.getString(SETTINGS, null) ?: return ReminderSettings()
        return try {
            ReminderSettingsJson.decode(JSONObject(json))
        } catch (e: Exception) {
            Log.w(TAG, "настройки напоминаний повреждены — применены значения по умолчанию", e)
            ReminderSettings()
        }
    }

    private fun persist(key: String, json: JSONObject) {
        if (!preferences.edit().putString(key, json.toString()).commit()) {
            throw IOException("не удалось сохранить настройки напоминаний")
        }
    }

    private companion object {
        const val TAG = "KardioReminder"
        const val PREFERENCES = "patient_reminders"
        const val SETTINGS = "settings"
        const val FACTS = "day_facts"
    }
}
