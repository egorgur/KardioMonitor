package ru.kardiomonitor.features.band.pairing

import kotlinx.coroutines.CancellationException
import ru.kardiomonitor.features.band.domain.Band
import ru.kardiomonitor.features.band.domain.BandCredentials
import ru.kardiomonitor.features.band.domain.BandRepository
import ru.kardiomonitor.features.clinic.domain.ClinicEnrollment
import ru.kardiomonitor.features.clinic.domain.EnrollmentResult
import java.time.Clock

/** Сохраняет QR v1 сразу, а QR v2 — только после успешной привязки к клинике. */
internal class PairScannedCode(
    private val repository: BandRepository,
    private val clinicEnrollment: ClinicEnrollment,
    private val clock: Clock,
) {
    suspend operator fun invoke(code: PairingCode): PairingResult {
        val invitation = code.invitation
        if (invitation != null) {
            val enrollment = clinicEnrollment.enroll(invitation.address, invitation.code)
            if (enrollment !is EnrollmentResult.Enrolled) return enrollment.toPairingResult()
        }
        return save(code.credentials)
    }

    fun save(credentials: BandCredentials): PairingResult = try {
        repository.save(Band.paired(credentials, at = clock.instant()))
        PairingResult.Paired
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        PairingResult.Failed(e.message ?: "не удалось безопасно сохранить данные браслета")
    }

    private fun EnrollmentResult.toPairingResult(): PairingResult = when (this) {
        EnrollmentResult.InvalidCode -> PairingResult.InvalidEnrollmentCode
        is EnrollmentResult.Failed -> PairingResult.Failed(reason)
        is EnrollmentResult.Enrolled -> error("успешная привязка обрабатывается до преобразования")
    }
}

/** Результат обработки QR или ручного ввода на экране привязки. */
internal sealed interface PairingResult {
    data object Paired : PairingResult
    data object InvalidEnrollmentCode : PairingResult
    data class Failed(val reason: String) : PairingResult
}
