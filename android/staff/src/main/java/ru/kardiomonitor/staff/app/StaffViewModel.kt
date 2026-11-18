package ru.kardiomonitor.staff.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.kardiomonitor.staff.features.bandcheck.domain.BandCheck
import ru.kardiomonitor.staff.features.bandcheck.domain.InspectionStage
import ru.kardiomonitor.staff.features.inventory.api.BandListEntry
import ru.kardiomonitor.staff.features.inventory.domain.BandAddress
import ru.kardiomonitor.staff.shared.BandProtocol
import ru.kardiomonitor.staff.features.inventory.domain.BondedBandCandidate
import ru.kardiomonitor.staff.features.inventory.domain.BandDiscoveryResult
import ru.kardiomonitor.staff.features.inventory.labeling.LabelBandResult
import ru.kardiomonitor.staff.features.inventory.manualentry.ManualEntryResult
import ru.kardiomonitor.staff.features.issuance.domain.Issuance
import ru.kardiomonitor.staff.features.issuance.qrdisplay.PrepareIssuanceResult

@OptIn(ExperimentalCoroutinesApi::class)
class StaffViewModel(private val container: AppContainer) : ViewModel() {
    private val query = MutableStateFlow("")
    val searchQuery: StateFlow<String> = query.asStateFlow()
    val bands: StateFlow<List<BandListEntry>> = query
        .flatMapLatest(container.observeBandList::invoke)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val checkState = MutableStateFlow(BandCheckUiState())
    val check: StateFlow<BandCheckUiState> = checkState.asStateFlow()

    private val entryProtocolState = MutableStateFlow<BandProtocol?>(null)
    val entryProtocol: StateFlow<BandProtocol?> = entryProtocolState.asStateFlow()
    private val discoveryState = MutableStateFlow<BandDiscoveryUiState>(BandDiscoveryUiState.Idle)
    val discovery: StateFlow<BandDiscoveryUiState> = discoveryState.asStateFlow()

    private val issuanceHistory = MutableStateFlow<List<Issuance>>(emptyList())
    val history: StateFlow<List<Issuance>> = issuanceHistory.asStateFlow()

    private val qrDisplay = MutableStateFlow<PrepareIssuanceResult.Ready?>(null)
    val code: StateFlow<PrepareIssuanceResult.Ready?> = qrDisplay.asStateFlow()
    private var prepareJob: Job? = null
    private var confirmJob: Job? = null
    private var checkJob: Job? = null
    private var discoveryJob: Job? = null

    private val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events = messages.asSharedFlow()

    init {
        refreshHistory()
    }

    fun search(value: String) {
        query.value = value
    }

    fun addBand(address: BandAddress, keyText: String, protocol: BandProtocol, onAdded: (BandAddress) -> Unit) {
        if (protocol != entryProtocolState.value || (discoveryState.value as? BandDiscoveryUiState.Found)?.address != address) return
        viewModelScope.launch {
            val result = try {
                container.addBandManually(address, keyText, protocol)
            } catch (_: ru.kardiomonitor.staff.features.inventory.domain.StoredKeyUnavailable) {
                messages.emit(KEY_UNAVAILABLE)
                return@launch
            }
            when (result) {
                is ManualEntryResult.Added -> {
                    messages.emit("Браслет добавлен")
                    onAdded(result.address)
                }
                ManualEntryResult.AddressMissing -> messages.emit("Не найден MAC-адрес")
                ManualEntryResult.KeyMissing -> messages.emit("Ключ или QR не соответствует выбранному адресу и протоколу")
                ManualEntryResult.ProtocolMismatch -> messages.emit("Протокол не совпадает с записью в реестре. Выберите правильную модель.")
            }
        }
    }

    fun selectProtocol(protocol: BandProtocol) {
        if (protocol == BandProtocol.Unknown || entryProtocolState.value == protocol) return
        cancelBandDiscovery()
        entryProtocolState.value = protocol
        discoveryState.value = BandDiscoveryUiState.Idle
    }

    fun chooseBondedBand(address: BandAddress) {
        if (entryProtocolState.value != BandProtocol.XiaomiBand9Spp) return
        val choices = discoveryState.value as? BandDiscoveryUiState.Choices ?: return
        val selected = choices.devices.singleOrNull { it.address == address } ?: return
        discoveryState.value = BandDiscoveryUiState.Found(selected.address, selected.name)
    }

    fun findBand() {
        if (discoveryJob != null) return
        val protocol = entryProtocolState.value ?: return
        discoveryState.value = BandDiscoveryUiState.Searching
        discoveryJob = viewModelScope.launch {
            try {
                val result = when (protocol) {
                    BandProtocol.MiBand6Ble -> container.bandDiscovery.nearestMiBand6()
                    BandProtocol.XiaomiBand9Spp -> container.classicBandDiscovery.alreadyBondedClassicDevices()
                    BandProtocol.Unknown -> return@launch
                }
                if (entryProtocolState.value == protocol) discoveryState.value = result.toUiState()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SecurityException) {
                discoveryState.value = BandDiscoveryUiState.Problem("Разрешите доступ к устройствам поблизости.")
            } finally {
                discoveryJob = null
            }
        }
    }

    fun cancelBandDiscovery() {
        discoveryJob?.cancel()
        discoveryState.value = BandDiscoveryUiState.Idle
    }

    fun label(address: BandAddress, text: String) {
        viewModelScope.launch {
            val message = when (container.labelBand(address, text)) {
                LabelBandResult.Saved -> "Метка сохранена"
                LabelBandResult.BandMissing -> "Браслет не найден"
                LabelBandResult.Invalid -> "Введите от 1 до 20 знаков"
                LabelBandResult.Duplicate -> "Такая метка уже используется"
            }
            messages.emit(message)
        }
    }

    fun inspect(address: BandAddress) {
        if (checkJob != null) return
        checkState.value = BandCheckUiState(address = address, running = true)
        checkJob = viewModelScope.launch {
            try {
                val result = container.checkBand(address) { stage ->
                    checkState.value = checkState.value.copy(currentStage = stage)
                }
                checkState.value = checkState.value.copy(running = false, result = result)
            } finally {
                checkJob = null
            }
        }
    }

    fun prepareCode(address: BandAddress, onReady: () -> Unit, onCheckRequired: () -> Unit) {
        if (prepareJob?.isActive == true) return
        prepareJob = viewModelScope.launch {
            try {
                when (val result = container.prepareIssuance(address)) {
                    is PrepareIssuanceResult.Ready -> {
                        qrDisplay.value = result
                        onReady()
                    }
                    PrepareIssuanceResult.KeyUnavailable -> messages.emit(KEY_UNAVAILABLE)
                    PrepareIssuanceResult.BandMissing -> messages.emit("Браслет не найден")
                    PrepareIssuanceResult.UnsupportedModel -> messages.emit("Эта модель не поддерживается для выдачи")
                    PrepareIssuanceResult.CheckRequired -> {
                        messages.emit("Сначала проверьте браслет с текущим ключом")
                        onCheckRequired()
                    }
                }
            } finally {
                prepareJob = null
            }
        }
    }

    fun confirmCodeDisplayed() {
        val display = qrDisplay.value ?: return
        if (confirmJob?.isActive == true) return
        confirmJob = viewModelScope.launch {
            if (container.confirmIssuance(display) == null) {
                qrDisplay.value = null
                messages.emit("QR устарел. Проверьте текущий ключ и откройте код заново.")
            }
            refreshHistory()
        }
    }

    fun clearCode() {
        prepareJob?.cancel()
        confirmJob?.cancel()
        qrDisplay.value = null
    }

    fun returnBand(address: BandAddress) {
        viewModelScope.launch {
            val returned = container.returnBand(address)
            messages.emit(if (returned) RETURN_REMINDER else "Открытая выдача не найдена")
            refreshHistory()
        }
    }

    fun refreshHistory() {
        viewModelScope.launch { issuanceHistory.value = container.loadIssuanceHistory() }
    }

    fun resetCheck() {
        if (checkJob == null) checkState.value = BandCheckUiState()
    }

    fun cancelCheck() {
        checkJob?.cancel()
        checkState.value = BandCheckUiState()
    }

    companion object {
        private const val KEY_UNAVAILABLE =
            "Ключ недоступен. Разблокируйте телефон и повторите. Не удаляйте данные приложения; при повторной ошибке обратитесь к администратору."
        private const val RETURN_REMINDER =
            "Возврат отмечен. Попросите прежнего пациента удалить KardioMonitor с телефона."
    }
}

data class BandCheckUiState(
    val address: BandAddress? = null,
    val running: Boolean = false,
    val currentStage: InspectionStage? = null,
    val result: BandCheck? = null,
)

sealed interface BandDiscoveryUiState {
    data object Idle : BandDiscoveryUiState
    data object Searching : BandDiscoveryUiState
    data class Found(val address: BandAddress, val name: String?) : BandDiscoveryUiState
    data class Choices(val devices: List<BondedBandCandidate>) : BandDiscoveryUiState
    data class Problem(val message: String) : BandDiscoveryUiState
}

private fun BandDiscoveryResult.toUiState(): BandDiscoveryUiState = when (this) {
    is BandDiscoveryResult.Found -> BandDiscoveryUiState.Found(address, advertisedName)
    is BandDiscoveryResult.Candidates -> BandDiscoveryUiState.Choices(devices)
    BandDiscoveryResult.NotFound -> BandDiscoveryUiState.Problem(
        "Mi Smart Band 6 не найден. Поднесите браслет ближе и закройте Mi Fitness.",
    )
    BandDiscoveryResult.NoPermission -> BandDiscoveryUiState.Problem("Разрешите доступ к устройствам поблизости.")
    BandDiscoveryResult.NoBluetooth -> BandDiscoveryUiState.Problem("На телефоне нет Bluetooth.")
    BandDiscoveryResult.BluetoothOff -> BandDiscoveryUiState.Problem("Включите Bluetooth и повторите поиск.")
    BandDiscoveryResult.ScanFailed -> BandDiscoveryUiState.Problem("Не удалось запустить поиск. Повторите ещё раз.")
}

class StaffViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = StaffViewModel(container) as T
}
