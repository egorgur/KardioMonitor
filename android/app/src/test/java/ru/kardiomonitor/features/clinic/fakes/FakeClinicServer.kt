package ru.kardiomonitor.features.clinic.fakes

import ru.kardiomonitor.features.clinic.domain.ClinicServer
import ru.kardiomonitor.features.clinic.domain.LiveHeartRateReport
import ru.kardiomonitor.features.clinic.domain.MinuteReport
import ru.kardiomonitor.features.clinic.domain.PatientName
import ru.kardiomonitor.features.clinic.domain.ServerAddress
import ru.kardiomonitor.features.clinic.domain.ServerRegistration
import ru.kardiomonitor.features.clinic.domain.UploadReceipt
import ru.kardiomonitor.features.clinic.domain.UploadToken
import java.io.IOException
import java.time.Instant
import java.time.ZoneId

/** Сервер в памяти: принятые минуты по времени начала, как на настоящем сервере (повтор перезаписывает). */
class FakeClinicServer : ClinicServer {
    val minutes = sortedMapOf<Instant, MinuteReport>()
    val batches = mutableListOf<List<MinuteReport>>()
    val liveHeartRate = mutableListOf<LiveHeartRateReport>()
    var registeredTimeZone: ZoneId? = null

    /** Не null — сервер недоступен с этим сообщением. */
    var failure: String? = null

    override suspend fun register(address: ServerAddress, name: PatientName, timeZone: ZoneId): ServerRegistration {
        failIfDown()
        registeredTimeZone = timeZone
        return ServerRegistration(address, "patient-1", name, UploadToken("token-1"))
    }

    override suspend fun lastUploadedMinute(registration: ServerRegistration): Instant? {
        failIfDown()
        return minutes.keys.lastOrNull()
    }

    override suspend fun uploadMinutes(registration: ServerRegistration, minutes: List<MinuteReport>): UploadReceipt {
        failIfDown()
        val new = minutes.count { it.start !in this.minutes }
        minutes.forEach { this.minutes[it.start] = it }
        batches += minutes
        return UploadReceipt(minutes.size, new, this.minutes.lastKey())
    }

    override suspend fun sendLiveHeartRate(registration: ServerRegistration, report: LiveHeartRateReport) {
        failIfDown()
        liveHeartRate += report
    }

    private fun failIfDown() {
        failure?.let { throw IOException(it) }
    }

    companion object {
        val REGISTRATION = ServerRegistration(
            ServerAddress.parse("https://demo.ngrok-free.app"),
            "patient-1",
            PatientName("Иван П."),
            UploadToken("token-1"),
        )
    }
}
