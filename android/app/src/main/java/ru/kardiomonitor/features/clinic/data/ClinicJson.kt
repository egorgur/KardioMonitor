package ru.kardiomonitor.features.clinic.data

import org.json.JSONArray
import org.json.JSONObject
import ru.kardiomonitor.features.clinic.domain.LiveHeartRateReport
import ru.kardiomonitor.features.clinic.domain.MinuteReport
import ru.kardiomonitor.features.clinic.domain.MinuteReport.ReportedKind
import ru.kardiomonitor.features.clinic.domain.EnrollmentCode
import ru.kardiomonitor.features.clinic.domain.PatientName
import ru.kardiomonitor.features.clinic.domain.ServerAddress
import ru.kardiomonitor.features.clinic.domain.ServerRegistration
import ru.kardiomonitor.features.clinic.domain.UploadReceipt
import ru.kardiomonitor.features.clinic.domain.UploadToken
import ru.kardiomonitor.features.clinic.domain.PhoneRehabPlanReport
import ru.kardiomonitor.features.clinic.domain.PhoneAlertFeed
import ru.kardiomonitor.features.clinic.domain.PhoneAlertReport
import ru.kardiomonitor.features.clinic.domain.PhoneLiveHeartRateRequest
import ru.kardiomonitor.features.clinic.domain.LiveHeartRateRequestStatusReport
import java.time.Instant
import java.time.ZoneId
import java.time.LocalDate

/**
 * JSON API v1 сервера (backend/docs/api-contract.md): имена полей — snake_case, время —
 * ISO 8601 с поясом (`Instant` — в UTC с `Z`), «замера нет» — `null`, а не 0.
 */
internal object ClinicJson {
    fun registrationRequest(name: PatientName, timeZone: ZoneId): JSONObject =
        JSONObject().put("name", name.value).put("time_zone", timeZone.id)

    fun enrollmentRequest(code: EnrollmentCode): JSONObject = JSONObject().put("code", code.value)

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

    fun liveHeartRateRequest(response: JSONObject): PhoneLiveHeartRateRequest? =
        if (response.isNull("request")) null else liveHeartRateRequestObject(response.getJSONObject("request"))

    fun liveHeartRateRequestStatus(report: LiveHeartRateRequestStatusReport): JSONObject = JSONObject()
        .put("status", report.status.wireName)
        .put("failure_reason", report.failureReason?.wireName ?: JSONObject.NULL)

    fun liveHeartRateRequestObject(response: JSONObject): PhoneLiveHeartRateRequest = PhoneLiveHeartRateRequest(
        requestId = response.getString("request_id"),
        durationMinutes = response.getInt("duration_minutes"),
        requestedAt = Instant.parse(response.getString("requested_at")),
        expiresAt = Instant.parse(response.getString("expires_at")),
        status = requestStatus(response.getString("status")),
        startedAt = nullableInstant(response, "started_at"),
        finishedAt = nullableInstant(response, "finished_at"),
        failureReason = nullableFailureReason(response),
    )

    fun rehabPlan(response: JSONObject): PhoneRehabPlanReport {
        val program = response.getJSONObject("program")
        val stages = program.getJSONArray("stages")
        return PhoneRehabPlanReport(
            timeZone = ZoneId.of(response.getString("time_zone")),
            effectiveFrom = LocalDate.parse(response.getString("effective_from")),
            walkingStepsThreshold = response.getInt("walking_steps_threshold"),
            startDate = LocalDate.parse(program.getString("start_date")),
            durationDays = program.getInt("duration_days"),
            stages = List(stages.length()) { index -> rehabStage(stages.getJSONObject(index)) },
        )
    }

    fun patientAlerts(response: JSONObject): PhoneAlertFeed {
        val alerts = response.getJSONArray("alerts")
        return PhoneAlertFeed(
            alerts = (0 until alerts.length()).mapNotNull { index -> patientAlert(alerts.getJSONObject(index)) },
            cursor = response.getLong("cursor"),
            hasMore = response.getBoolean("has_more"),
        )
    }

    private fun patientAlert(json: JSONObject): PhoneAlertReport? {
        // Будущий вид пропускаем до чтения его полей, сохраняя курсор всей серверной страницы.
        val kind = PhoneAlertReport.Kind.entries.firstOrNull { it.wireName == json.getString("kind") } ?: return null
        return PhoneAlertReport(
            alertId = json.getString("alert_id"),
            kind = kind,
            day = if (json.isNull("day")) null else LocalDate.parse(json.getString("day")),
            createdAt = Instant.parse(json.getString("created_at")),
            highHeartRate = if (kind == PhoneAlertReport.Kind.HighHeartRate) highHeartRate(json) else null,
            dailyTarget = if (kind in DAILY_TARGET_KINDS) dailyTarget(json) else null,
            lowActivity = if (kind == PhoneAlertReport.Kind.LowActivity) lowActivity(json) else null,
            trainingMinutesExceeded = if (kind == PhoneAlertReport.Kind.TrainingMinutesExceeded) {
                trainingMinutesExceeded(json)
            } else null,
        )
    }

    private fun highHeartRate(json: JSONObject): PhoneAlertReport.HighHeartRate {
        val details = json.getJSONObject("high_heart_rate")
        return PhoneAlertReport.HighHeartRate(
            startedAt = Instant.parse(details.getString("started_at")),
            lastAt = Instant.parse(details.getString("last_at")),
            durationMinutes = details.getInt("duration_minutes"),
            maxHeartRate = details.getInt("max_heart_rate"),
            thresholdBpm = details.getInt("threshold_bpm"),
        )
    }

    private fun dailyTarget(json: JSONObject): PhoneAlertReport.DailyTarget {
        val details = json.getJSONObject("daily_target")
        return PhoneAlertReport.DailyTarget(
            trainingMinutes = details.getInt("training_minutes"),
            targetTrainingMinutes = details.getInt("target_training_minutes"),
            steps = details.getInt("steps"),
            targetSteps = details.getInt("target_steps"),
        )
    }

    private fun lowActivity(json: JSONObject): PhoneAlertReport.LowActivity {
        val details = json.getJSONObject("low_activity")
        return PhoneAlertReport.LowActivity(details.getInt("steps"), details.getInt("threshold_steps"))
    }

    private fun trainingMinutesExceeded(json: JSONObject): PhoneAlertReport.TrainingMinutesExceeded {
        val details = json.getJSONObject("training_minutes_exceeded")
        return PhoneAlertReport.TrainingMinutesExceeded(details.getInt("training_minutes"), details.getInt("threshold_minutes"))
    }

    private fun requestStatus(value: String): PhoneLiveHeartRateRequest.Status =
        PhoneLiveHeartRateRequest.Status.entries.firstOrNull { it.wireName == value }
            ?: throw IllegalArgumentException("неизвестное состояние запроса пульса: $value")

    private fun nullableFailureReason(response: JSONObject): PhoneLiveHeartRateRequest.FailureReason? {
        if (response.isNull("failure_reason")) return null
        val value = response.getString("failure_reason")
        return PhoneLiveHeartRateRequest.FailureReason.entries.firstOrNull { it.wireName == value }
            ?: throw IllegalArgumentException("неизвестная причина сбоя запроса пульса: $value")
    }

    private fun nullableInstant(response: JSONObject, name: String): Instant? =
        if (response.isNull(name)) null else Instant.parse(response.getString(name))

    private fun rehabStage(stage: JSONObject): PhoneRehabPlanReport.Stage {
        val targets = stage.getJSONObject("targets")
        val zone = targets.getJSONObject("training_zone")
        return PhoneRehabPlanReport.Stage(
            firstDay = stage.getInt("first_day"),
            lastDay = stage.getInt("last_day"),
            lowerBpm = zone.getInt("lower_bpm"),
            upperBpm = zone.getInt("upper_bpm"),
            trainingMinutes = targets.getInt("training_minutes"),
            steps = targets.getInt("steps"),
        )
    }

    private fun kindName(kind: ReportedKind): String = when (kind) {
        ReportedKind.NotWorn -> "not_worn"
        ReportedKind.Charging -> "charging"
        ReportedKind.Sleep -> "sleep"
        ReportedKind.Other -> "other"
    }

    private val DAILY_TARGET_KINDS = setOf(
        PhoneAlertReport.Kind.DailyTargetMissed,
        PhoneAlertReport.Kind.DailyTargetMet,
    )
}
