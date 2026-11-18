package ru.kardiomonitor.features.clinic.data

import org.json.JSONException
import org.json.JSONObject
import ru.kardiomonitor.features.clinic.domain.ClinicServer
import ru.kardiomonitor.features.clinic.domain.EnrollmentCode
import ru.kardiomonitor.features.clinic.domain.LiveHeartRateReport
import ru.kardiomonitor.features.clinic.domain.MinuteReport
import ru.kardiomonitor.features.clinic.domain.PatientName
import ru.kardiomonitor.features.clinic.domain.ServerAddress
import ru.kardiomonitor.features.clinic.domain.ServerRegistration
import ru.kardiomonitor.features.clinic.domain.UploadReceipt
import ru.kardiomonitor.features.clinic.domain.PhoneRehabPlanReport
import ru.kardiomonitor.features.clinic.domain.PhoneAlertFeed
import ru.kardiomonitor.features.clinic.domain.PhoneAlertReport
import ru.kardiomonitor.features.clinic.domain.PhoneLiveHeartRateRequest
import ru.kardiomonitor.features.clinic.domain.LiveHeartRateRequestStatusReport
import ru.kardiomonitor.platform.http.HttpProblemException
import ru.kardiomonitor.platform.http.JsonHttpClient
import java.io.IOException
import java.time.Instant
import java.time.DateTimeException
import java.time.ZoneId

/** Сервер клиники по HTTP (API v1). Неожиданный ответ — [IOException], как и сбой сети. */
class HttpClinicServer(private val http: JsonHttpClient) : ClinicServer {
    override suspend fun register(address: ServerAddress, name: PatientName, timeZone: ZoneId): ServerRegistration {
        val response = http.post("${address.url}/api/v1/register", ClinicJson.registrationRequest(name, timeZone))
        return parsing { ClinicJson.registration(address, required(response)) }
    }

    override suspend fun enroll(address: ServerAddress, code: EnrollmentCode): ServerRegistration? {
        return try {
            val response = http.post("${address.url}/api/v1/enroll", ClinicJson.enrollmentRequest(code))
            parsing { ClinicJson.registration(address, required(response)) }
        } catch (e: HttpProblemException) {
            if (e.status == 422 && e.code == INVALID_ENROLLMENT_CODE) null else throw e
        }
    }

    override suspend fun lastUploadedMinute(registration: ServerRegistration): Instant? {
        val response = http.get(url(registration, "/api/v1/me/activity-minutes/last"), registration.token.value)
        return parsing { ClinicJson.uploadCursor(required(response)) }
    }

    override suspend fun uploadMinutes(registration: ServerRegistration, minutes: List<MinuteReport>): UploadReceipt {
        val response = http.post(
            url(registration, "/api/v1/me/activity-minutes"),
            ClinicJson.minuteBatch(minutes),
            registration.token.value,
        )
        return parsing { ClinicJson.receipt(required(response)) }
    }

    override suspend fun sendLiveHeartRate(registration: ServerRegistration, report: LiveHeartRateReport) {
        http.post(url(registration, "/api/v1/me/live-heart-rate"), ClinicJson.liveHeartRate(report), registration.token.value)
    }

    override suspend fun rehabPlan(registration: ServerRegistration): PhoneRehabPlanReport {
        val response = http.get(url(registration, "/api/v1/me/rehab-plan"), registration.token.value)
        return parsing { ClinicJson.rehabPlan(required(response)) }
    }

    override suspend fun patientAlerts(registration: ServerRegistration, after: Long): PhoneAlertFeed {
        val supportedKinds = PhoneAlertReport.Kind.entries.joinToString("&") { "supported_kinds=${it.wireName}" }
        val response = http.get(url(registration, "/api/v1/me/alerts?after=$after&$supportedKinds"), registration.token.value)
        return parsing { ClinicJson.patientAlerts(required(response)) }
    }

    override suspend fun activeLiveHeartRateRequest(registration: ServerRegistration): PhoneLiveHeartRateRequest? {
        val response = http.get(url(registration, "/api/v1/me/live-heart-rate-request"), registration.token.value)
        return parsing { ClinicJson.liveHeartRateRequest(required(response)) }
    }

    override suspend fun reportLiveHeartRateRequestStatus(
        registration: ServerRegistration,
        requestId: String,
        report: LiveHeartRateRequestStatusReport,
    ): PhoneLiveHeartRateRequest {
        val path = "/api/v1/me/live-heart-rate-requests/$requestId/status"
        val response = http.put(url(registration, path), ClinicJson.liveHeartRateRequestStatus(report), registration.token.value)
        return parsing { ClinicJson.liveHeartRateRequestObject(required(response)) }
    }

    private fun url(registration: ServerRegistration, path: String): String = registration.address.url + path

    private fun required(response: JSONObject?): JSONObject = response ?: throw IOException("сервер вернул пустой ответ")

    /** Ответ не по контракту (нет поля, неверное время или имя) — сбой обмена, а не падение. */
    private inline fun <T> parsing(block: () -> T): T = try {
        block()
    } catch (e: JSONException) {
        throw IOException("ответ сервера не по контракту: ${e.message}", e)
    } catch (e: DateTimeException) {
        throw IOException("ответ сервера не по контракту: ${e.message}", e)
    } catch (e: IllegalArgumentException) {
        throw IOException("ответ сервера не по контракту: ${e.message}", e)
    }

    private companion object {
        const val INVALID_ENROLLMENT_CODE = "invalid_enrollment_code"
    }
}
