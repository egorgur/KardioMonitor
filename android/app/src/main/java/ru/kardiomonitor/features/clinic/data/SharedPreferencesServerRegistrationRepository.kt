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
import ru.kardiomonitor.platform.keystore.EncryptedPreferences
import ru.kardiomonitor.platform.keystore.SecretBox
import java.io.IOException

/** Регистрация в SharedPreferences `clinic_server`; токен зашифрован через Android Keystore. */
class SharedPreferencesServerRegistrationRepository(context: Context, secretBox: SecretBox) : ServerRegistrationRepository {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val encryptedRegistration = EncryptedPreferences(prefs, secretBox)
    private val current = MutableStateFlow(load())

    override val registration: StateFlow<ServerRegistration?> = current.asStateFlow()

    override fun save(registration: ServerRegistration) {
        val encryptedToken = encryptedRegistration.seal(registration.token.value)
        val saved = prefs.edit()
            .putString(ADDRESS, registration.address.url)
            .putString(PATIENT_ID, registration.patientId)
            .putString(PATIENT_NAME, registration.patientName.value)
            .putString(ENCRYPTED_TOKEN, encryptedToken)
            .remove(TOKEN)
            .commit()
        if (!saved) throw IOException("не удалось сохранить привязку к серверу")
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
        return try {
            val token = encryptedRegistration.get(ENCRYPTED_TOKEN, TOKEN) ?: return null
            ServerRegistration(ServerAddress.parse(address), patientId, PatientName(name), UploadToken(token))
        } catch (e: Exception) {
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
        const val ENCRYPTED_TOKEN = "token_sealed"
    }
}
