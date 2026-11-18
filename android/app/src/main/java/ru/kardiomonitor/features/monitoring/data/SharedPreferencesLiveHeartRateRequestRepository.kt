package ru.kardiomonitor.features.monitoring.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import ru.kardiomonitor.features.monitoring.domain.LiveHeartRateRequest
import ru.kardiomonitor.features.monitoring.domain.LiveHeartRateRequestRepository
import java.io.IOException
import java.time.Instant

/** Один запрос и его outbox в SharedPreferences; commit предшествует запуску Worker. */
class SharedPreferencesLiveHeartRateRequestRepository(context: Context) : LiveHeartRateRequestRepository {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val current = MutableStateFlow<LiveHeartRateRequest?>(null)
    private var selectedPatientId: String? = null

    override val request: StateFlow<LiveHeartRateRequest?> = current.asStateFlow()

    override fun selectPatient(patientId: String?) {
        if (selectedPatientId == patientId) return
        selectedPatientId = patientId
        current.value = load(patientId)
    }

    override fun save(patientId: String, request: LiveHeartRateRequest) {
        require(selectedPatientId == patientId) { "выбран другой пациент" }
        val saved = preferences.edit()
            .putString(PATIENT_ID, patientId)
            .putString(REQUEST, LiveHeartRateRequestJson.encode(request).toString())
            .commit()
        if (!saved) throw IOException("не удалось сохранить запрос пульса клиники")
        current.value = request
    }

    private fun load(patientId: String?): LiveHeartRateRequest? {
        if (patientId == null || preferences.getString(PATIENT_ID, null) != patientId) return null
        val text = preferences.getString(REQUEST, null) ?: return null
        return try {
            LiveHeartRateRequestJson.decode(JSONObject(text))
        } catch (e: Exception) {
            Log.w(TAG, "сохранённый запрос пульса повреждён", e)
            null
        }
    }

    private companion object {
        const val TAG = "KardioServer"
        const val PREFERENCES = "live_heart_rate_request"
        const val PATIENT_ID = "patient_id"
        const val REQUEST = "request"
    }
}

internal object LiveHeartRateRequestJson {
    fun encode(request: LiveHeartRateRequest): JSONObject = JSONObject()
        .put("id", request.id)
        .put("duration_minutes", request.durationMinutes)
        .put("requested_at", request.requestedAt.toString())
        .put("expires_at", request.expiresAt.toString())
        .put("status", request.status.name)
        .put("started_at", request.startedAt?.toString() ?: JSONObject.NULL)
        .put("reports", JSONArray(request.reports.map(::encodeReport)))

    fun decode(json: JSONObject): LiveHeartRateRequest {
        val reports = json.getJSONArray("reports")
        return LiveHeartRateRequest(
            id = json.getString("id"),
            durationMinutes = json.getInt("duration_minutes"),
            requestedAt = Instant.parse(json.getString("requested_at")),
            expiresAt = Instant.parse(json.getString("expires_at")),
            status = LiveHeartRateRequest.Status.valueOf(json.getString("status")),
            startedAt = if (json.isNull("started_at")) null else Instant.parse(json.getString("started_at")),
            reports = List(reports.length()) { decodeReport(reports.getJSONObject(it)) },
        )
    }

    private fun encodeReport(report: LiveHeartRateRequest.Report): JSONObject = JSONObject()
        .put("status", report.status.name)
        .put("failure_reason", report.failureReason?.name ?: JSONObject.NULL)

    private fun decodeReport(json: JSONObject): LiveHeartRateRequest.Report = LiveHeartRateRequest.Report(
        status = LiveHeartRateRequest.Status.valueOf(json.getString("status")),
        failureReason = if (json.isNull("failure_reason")) null else {
            LiveHeartRateRequest.FailureReason.valueOf(json.getString("failure_reason"))
        },
    )
}
