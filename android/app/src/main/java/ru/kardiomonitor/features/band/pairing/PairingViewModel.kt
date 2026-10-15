package ru.kardiomonitor.features.band.pairing

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import ru.kardiomonitor.features.band.api.BandConnection
import ru.kardiomonitor.features.band.domain.Band
import ru.kardiomonitor.features.band.domain.BandCredentials
import ru.kardiomonitor.features.band.domain.BandRepository

/** Состояние экрана привязки. [manualCredentials] — null, пока введённое не распознаётся. */
internal data class PairingUiState(
    val mac: String = "",
    val key: String = "",
    val manualCredentials: BandCredentials? = null,
    val scanProblem: String? = null,
)

internal class PairingViewModel(
    private val repository: BandRepository,
    private val connection: BandConnection,
) : ViewModel() {
    private val current = MutableStateFlow(PairingUiState())
    val state: StateFlow<PairingUiState> = current.asStateFlow()

    fun onMacChange(mac: String) = updateManual(mac = mac.trim(), key = current.value.key)

    fun onKeyChange(key: String) = updateManual(mac = current.value.mac, key = key.trim())

    fun onScanStarted() = current.update { it.copy(scanProblem = null) }

    /** Содержимое QR-кода клиники. */
    fun onScanned(text: String) {
        val credentials = BandCredentials.parse(text)
        if (credentials == null) {
            current.update { it.copy(scanProblem = "В QR-коде нет MAC-адреса и ключа") }
        } else {
            pair(credentials)
        }
    }

    fun saveManual() {
        current.value.manualCredentials?.let(::pair)
    }

    /** «Сменить браслет»: соединение закрывается, учётные данные и настройки удаляются. */
    fun forget() {
        connection.disconnect()
        repository.forget()
        current.value = PairingUiState()
    }

    private fun pair(credentials: BandCredentials) = repository.save(Band.paired(credentials))

    private fun updateManual(mac: String, key: String) = current.update {
        it.copy(mac = mac, key = key, manualCredentials = BandCredentials.parse("$mac $key"))
    }
}
