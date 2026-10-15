package ru.kardiomonitor.features.clinic.registration

import android.util.Log
import ru.kardiomonitor.features.clinic.domain.ClinicServer
import ru.kardiomonitor.features.clinic.domain.PatientName
import ru.kardiomonitor.features.clinic.domain.ServerAddress
import ru.kardiomonitor.features.clinic.domain.ServerRegistration
import ru.kardiomonitor.features.clinic.domain.ServerRegistrationRepository
import java.io.IOException
import java.time.ZoneId

/** Итог регистрации на сервере. */
internal sealed interface RegistrationResult {
    data class Registered(val registration: ServerRegistration) : RegistrationResult

    data class Failed(val reason: String) : RegistrationResult
}

/**
 * Регистрация телефона на сервере клиники: имя пациента и часовой пояс телефона → токен
 * загрузки, который сохраняется в [ServerRegistrationRepository].
 */
internal class RegisterOnServer(
    private val server: ClinicServer,
    private val repository: ServerRegistrationRepository,
    private val timeZone: () -> ZoneId = ZoneId::systemDefault,
) {
    suspend operator fun invoke(addressInput: String, nameInput: String): RegistrationResult {
        val (address, name) = try {
            ServerAddress.parse(addressInput) to PatientName(nameInput.trim())
        } catch (e: IllegalArgumentException) {
            return RegistrationResult.Failed(e.message ?: "неверные данные")
        }
        return try {
            val registration = server.register(address, name, timeZone())
            repository.save(registration)
            Log.i(TAG, "телефон зарегистрирован на ${address.url}")
            RegistrationResult.Registered(registration)
        } catch (e: IOException) {
            Log.w(TAG, "регистрация на сервере не удалась", e)
            RegistrationResult.Failed(e.message ?: "сервер недоступен")
        }
    }

    private companion object {
        const val TAG = "KardioServer"
    }
}
