package ru.kardiomonitor.features.alerts.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import ru.kardiomonitor.features.alerts.domain.PatientAlert
import ru.kardiomonitor.features.alerts.domain.PatientAlertRepository
import java.io.IOException
import java.time.Instant
import java.time.LocalDate

/** Журнал одного владельца телефона в SharedPreferences; повторный alert_id обновляет запись. */
class SharedPreferencesPatientAlertRepository(context: Context) : PatientAlertRepository {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val current = MutableStateFlow<List<PatientAlert>>(emptyList())
    private var selectedPatientId: String? = null
    private var archive = PatientAlertArchive()

    override val alerts: StateFlow<List<PatientAlert>> = current.asStateFlow()

    override fun selectPatient(patientId: String?) {
        if (selectedPatientId == patientId) return
        selectedPatientId = patientId
        archive = load(patientId)
        publish()
    }

    override fun cursor(patientId: String): Long {
        requireSelected(patientId)
        return archive.cursor
    }

    override fun save(patientId: String, page: List<PatientAlert>, cursor: Long): List<PatientAlert> {
        requireSelected(patientId)
        val knownIds = archive.alerts.mapTo(mutableSetOf()) { it.id }
        val incomingIds = page.mapTo(mutableSetOf()) { it.id }
        val kept = archive.alerts.filterNot { it.id in incomingIds }
        archive = archive.copy(alerts = kept + page, cursor = maxOf(archive.cursor, cursor))
        persist(patientId)
        publish()
        return page.filterNot { it.id in knownIds }
    }

    override fun wasNotified(patientId: String, alertId: String): Boolean {
        requireSelected(patientId)
        return alertId in archive.notifiedIds
    }

    override fun markNotified(patientId: String, alertId: String) {
        requireSelected(patientId)
        if (alertId in archive.notifiedIds) return
        archive = archive.copy(notifiedIds = archive.notifiedIds + alertId)
        persist(patientId)
    }

    private fun publish() {
        current.value = archive.alerts.sortedByDescending(PatientAlert::createdAt)
    }

    private fun requireSelected(patientId: String) {
        require(selectedPatientId == patientId) { "выбран другой пациент" }
    }

    private fun persist(patientId: String) {
        val saved = preferences.edit()
            .putString(PATIENT_ID, patientId)
            .putString(ARCHIVE, PatientAlertJson.encodeArchive(archive).toString())
            .commit()
        if (!saved) throw IOException("не удалось сохранить оповещения клиники")
    }

    private fun load(patientId: String?): PatientAlertArchive {
        if (patientId == null || preferences.getString(PATIENT_ID, null) != patientId) return PatientAlertArchive()
        val text = preferences.getString(ARCHIVE, null) ?: return PatientAlertArchive()
        return try {
            PatientAlertJson.decodeArchive(JSONObject(text))
        } catch (e: Exception) {
            Log.w(TAG, "сохранённые оповещения повреждены — будут загружены снова", e)
            PatientAlertArchive()
        }
    }

    private companion object {
        const val TAG = "KardioServer"
        const val PREFERENCES = "patient_alerts"
        const val PATIENT_ID = "patient_id"
        const val ARCHIVE = "archive"
    }
}

internal data class PatientAlertArchive(
    val alerts: List<PatientAlert> = emptyList(),
    val cursor: Long = 0,
    val notifiedIds: Set<String> = emptySet(),
)

internal object PatientAlertJson {
    fun encodeArchive(archive: PatientAlertArchive): JSONObject = JSONObject()
        .put("cursor", archive.cursor)
        .put("notified_ids", JSONArray(archive.notifiedIds.toList()))
        .put("alerts", JSONArray(archive.alerts.map(::encodeAlert)))

    fun decodeArchive(json: JSONObject): PatientAlertArchive {
        val alerts = json.getJSONArray("alerts")
        val notified = json.getJSONArray("notified_ids")
        return PatientAlertArchive(
            alerts = List(alerts.length()) { decodeAlert(alerts.getJSONObject(it)) },
            cursor = json.getLong("cursor"),
            notifiedIds = List(notified.length()) { notified.getString(it) }.toSet(),
        )
    }

    private fun encodeAlert(alert: PatientAlert): JSONObject = JSONObject()
        .put("id", alert.id)
        .put("kind", alert.kind.name)
        .put("day", alert.day?.toString() ?: JSONObject.NULL)
        .put("created_at", alert.createdAt.toString())
        .put("details", encodeDetails(alert.details))

    private fun decodeAlert(json: JSONObject): PatientAlert = PatientAlert(
        id = json.getString("id"),
        kind = PatientAlert.Kind.valueOf(json.getString("kind")),
        day = if (json.isNull("day")) null else LocalDate.parse(json.getString("day")),
        createdAt = Instant.parse(json.getString("created_at")),
        details = decodeDetails(json.getJSONObject("details")),
    )

    private fun encodeDetails(details: PatientAlert.Details): JSONObject = when (details) {
        is PatientAlert.Details.HighHeartRate -> JSONObject()
            .put("type", "high_heart_rate")
            .put("started_at", details.startedAt.toString())
            .put("last_at", details.lastAt.toString())
            .put("duration_minutes", details.durationMinutes)
            .put("max_heart_rate", details.maxHeartRate)
            .put("threshold_bpm", details.thresholdBpm)
        is PatientAlert.Details.DailyTarget -> JSONObject()
            .put("type", "daily_target")
            .put("training_minutes", details.trainingMinutes)
            .put("target_training_minutes", details.targetTrainingMinutes)
            .put("steps", details.steps)
            .put("target_steps", details.targetSteps)
        is PatientAlert.Details.TrainingMinutesExceeded -> JSONObject()
            .put("type", "training_minutes_exceeded")
            .put("training_minutes", details.trainingMinutes)
            .put("threshold_minutes", details.thresholdMinutes)
        is PatientAlert.Details.LowActivity -> JSONObject()
            .put("type", "low_activity")
            .put("steps", details.steps)
            .put("threshold_steps", details.thresholdSteps)
    }

    private fun decodeDetails(json: JSONObject): PatientAlert.Details = when (json.getString("type")) {
        "high_heart_rate" -> PatientAlert.Details.HighHeartRate(
            Instant.parse(json.getString("started_at")),
            Instant.parse(json.getString("last_at")),
            json.getInt("duration_minutes"),
            json.getInt("max_heart_rate"),
            json.getInt("threshold_bpm"),
        )
        "daily_target" -> PatientAlert.Details.DailyTarget(
            json.getInt("training_minutes"),
            json.getInt("target_training_minutes"),
            json.getInt("steps"),
            json.getInt("target_steps"),
        )
        "training_minutes_exceeded" -> PatientAlert.Details.TrainingMinutesExceeded(
            json.getInt("training_minutes"),
            json.getInt("threshold_minutes"),
        )
        "low_activity" -> PatientAlert.Details.LowActivity(json.getInt("steps"), json.getInt("threshold_steps"))
        else -> throw IllegalArgumentException("неизвестные значения оповещения")
    }
}
