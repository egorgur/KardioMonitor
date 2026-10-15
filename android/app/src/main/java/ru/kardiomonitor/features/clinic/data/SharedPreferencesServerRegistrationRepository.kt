package ru.kardiomonitor.features.clinic.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.kardiomonitor.features.clinic.domain.PatientName
import ru.kardiomonitor.features.clinic.domain.ServerAddress
import ru.kardiomonitor.features.clinic.domain.ServerRegistration
import ru.kardiomonitor.features.clinic.domain.ServerRegistrationRepository
import ru.kardiomonitor.features.clinic.domain.UploadToken

/** Регистрация на сервере в SharedPreferences `clinic_server` (план — Keystore, как ключ браслета). */
class SharedPreferencesServerRegistrationRepository(context: Context) : ServerRegistrationRepository {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val current = MutableStateFlow(load())

    override val registration: StateFlow<ServerRegistration?> = current.asStateFlow()

    override fun save(registration: ServerRegistration) {
        prefs.edit()
            .putString(ADDRESS, registration.address.url)
            .putString(PATIENT_ID, registration.patientId)
            .putString(PATIENT_NAME, registration.patientName.value)
            .putString(TOKEN, registration.token.value)
            .apply()
        current.value = registration
    }

    override fun forget() {
        prefs.edit().clear().apply()
        current.value = null
    }

    private fun load(): ServerRegistration? {
        val address = prefs.getString(ADDRESS, null) ?: return null
        val patientId = prefs.getString(PATIENT_ID, null) ?: return null
        val name = prefs.getString(PATIENT_NAME, null) ?: return null
        val token = prefs.getString(TOKEN, null) ?: return null
        return try {
            ServerRegistration(ServerAddress.parse(address), patientId, PatientName(name), UploadToken(token))
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "сохранённая регистрация на сервере повреждена — нужна новая", e)
            null
        }
    }

    private companion object {
        const val TAG = "KardioServer"
        const val PREFS = "clinic_server"
        const val ADDRESS = "address"
        const val PATIENT_ID = "patient_id"
        const val PATIENT_NAME = "patient_name"
        const val TOKEN = "token"
    }
}
