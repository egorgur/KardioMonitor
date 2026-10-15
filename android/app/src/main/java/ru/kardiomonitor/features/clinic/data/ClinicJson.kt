package ru.kardiomonitor.features.clinic.data

import org.json.JSONArray
import org.json.JSONObject
import ru.kardiomonitor.features.clinic.domain.LiveHeartRateReport
import ru.kardiomonitor.features.clinic.domain.MinuteReport
import ru.kardiomonitor.features.clinic.domain.MinuteReport.ReportedKind
import ru.kardiomonitor.features.clinic.domain.PatientName
import ru.kardiomonitor.features.clinic.domain.ServerAddress
import ru.kardiomonitor.features.clinic.domain.ServerRegistration
import ru.kardiomonitor.features.clinic.domain.UploadReceipt
import ru.kardiomonitor.features.clinic.domain.UploadToken
import java.time.Instant
import java.time.ZoneId

/**
 * JSON API v1 сервера (backend/docs/api-contract.md): имена полей — snake_case, время —
 * ISO 8601 с поясом (`Instant` — в UTC с `Z`), «замера нет» — `null`, а не 0.
 */
internal object ClinicJson {
    fun registrationRequest(name: PatientName, timeZone: ZoneId): JSONObject =
        JSONObject().put("name", name.value).put("time_zone", timeZone.id)

    fun registration(address: ServerAddress, response: JSONObject): ServerRegistration = ServerRegistration(
        address = address,
        patientId = response.getString("patient_id"),
        patientName = PatientName(response.getString("name")),
        token = UploadToken(response.getString("token")),
    )

    fun minuteBatch(minutes: List<MinuteReport>): JSONObject =
        JSONObject().put("minutes", JSONArray(minutes.map(::minute)))

    fun minute(report: MinuteReport): JSONObject = JSONObject()
        .put("started_at", report.start.toString())
        .put("steps", report.steps)
        .put("heart_rate", report.heartRateBpm ?: JSONObject.NULL)
        .put("kind", kindName(report.kind))
        .put("kind_code", report.kindCode ?: JSONObject.NULL)
        .put("intensity", report.intensity)

    fun receipt(response: JSONObject): UploadReceipt = UploadReceipt(
        received = response.getInt("received"),
        new = response.getInt("new"),
        lastMinute = Instant.parse(response.getString("last_minute_started_at")),
    )

    /** `{"started_at": null}` — сервер ещё не принял ни одной минуты. */
    fun uploadCursor(response: JSONObject): Instant? =
        if (response.isNull("started_at")) null else Instant.parse(response.getString("started_at"))

    fun liveHeartRate(report: LiveHeartRateReport): JSONObject =
        JSONObject().put("measured_at", report.measuredAt.toString()).put("heart_rate", report.bpm)

    private fun kindName(kind: ReportedKind): String = when (kind) {
        ReportedKind.NotWorn -> "not_worn"
        ReportedKind.Charging -> "charging"
        ReportedKind.Sleep -> "sleep"
        ReportedKind.Other -> "other"
    }
}
