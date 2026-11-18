package ru.kardiomonitor.features.clinic.enrollment

import android.util.Log
import kotlinx.coroutines.CancellationException
import ru.kardiomonitor.features.clinic.domain.ClinicEnrollment
import ru.kardiomonitor.features.clinic.domain.ClinicServer
import ru.kardiomonitor.features.clinic.domain.EnrollmentCode
import ru.kardiomonitor.features.clinic.domain.EnrollmentResult
import ru.kardiomonitor.features.clinic.domain.ServerAddress
import ru.kardiomonitor.features.clinic.domain.ServerRegistrationRepository

/** Точка входа привязки к клинике: обменивает одноразовый код и сохраняет регистрацию. */
class StoredClinicEnrollment(
    private val server: ClinicServer,
    private val repository: ServerRegistrationRepository,
) : ClinicEnrollment {
    override suspend fun enroll(address: ServerAddress, code: EnrollmentCode): EnrollmentResult {
        return try {
            val registration = server.enroll(address, code) ?: return EnrollmentResult.InvalidCode
            repository.save(registration)
            Log.i(TAG, "телефон привязан к пациенту на ${address.url}")
            EnrollmentResult.Enrolled(registration)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "привязка телефона к пациенту не удалась", e)
            EnrollmentResult.Failed(e.message ?: "не удалось сохранить привязку")
        }
    }

    private companion object {
        const val TAG = "KardioServer"
    }
}
