package ru.kardiomonitor.features.band.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.kardiomonitor.features.band.domain.BandCredentials
import ru.kardiomonitor.features.band.domain.BandRepository
import ru.kardiomonitor.features.band.domain.BandProtocol
import ru.kardiomonitor.features.clinic.domain.ClinicEnrollment
import java.time.Clock

/** Состояние экрана привязки. [manualCredentials] — null, пока введённое не распознаётся. */
internal data class PairingUiState(
    val protocol: BandProtocol = BandProtocol.MiBand6Ble,
    val mac: String = "",
    val key: String = "",
    val manualCredentials: BandCredentials? = null,
    val scanProblem: String? = null,
    val isPairing: Boolean = false,
)

internal class PairingViewModel(
    private val repository: BandRepository,
    private val clinicEnrollment: ClinicEnrollment,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {
    private val current = MutableStateFlow(PairingUiState())
    private val pairScannedCode = PairScannedCode(repository, clinicEnrollment, clock)
    val state: StateFlow<PairingUiState> = current.asStateFlow()

    fun onMacChange(mac: String) = updateManual(mac = mac.trim(), key = current.value.key)

    fun onKeyChange(key: String) = updateManual(mac = current.value.mac, key = key.trim())

    fun onProtocolChange(protocol: BandProtocol) {
        current.update { it.copy(protocol = protocol) }
        updateManual(current.value.mac, current.value.key)
    }

    fun onScanStarted() = current.update { it.copy(scanProblem = null) }

    /** Содержимое QR-кода клиники. */
    fun onScanned(text: String) {
        val code = PairingCode.parse(text)
        if (code == null) {
            current.update { it.copy(scanProblem = "QR-код повреждён или имеет неверный формат") }
            return
        }
        if (code.invitation == null) return onPairingResult(pairScannedCode.save(code.credentials))
        current.update { it.copy(isPairing = true, scanProblem = null) }
        viewModelScope.launch {
            onPairingResult(pairScannedCode(code))
        }
    }

    fun saveManual() {
        current.value.manualCredentials?.let { onPairingResult(pairScannedCode.save(it)) }
    }

    private fun onPairingResult(result: PairingResult) {
        when (result) {
            PairingResult.Paired -> current.update {
                it.copy(mac = "", key = "", manualCredentials = null, isPairing = false)
            }
            PairingResult.InvalidEnrollmentCode -> current.update {
                it.copy(isPairing = false, scanProblem = "Код привязки истёк или уже использован")
            }
            is PairingResult.Failed -> current.update {
                it.copy(isPairing = false, scanProblem = result.reason)
            }
        }
    }

    private fun updateManual(mac: String, key: String) = current.update {
        it.copy(mac = mac, key = key, manualCredentials = BandCredentials.parse("$mac $key")?.copy(protocol = it.protocol))
    }
}
