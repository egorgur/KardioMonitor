package ru.kardiomonitor.staff.features.inventory.domain

sealed interface BandDiscoveryResult {
    data class Found(val address: BandAddress, val advertisedName: String?) : BandDiscoveryResult
    data class Candidates(val devices: List<BondedBandCandidate>) : BandDiscoveryResult
    data object NotFound : BandDiscoveryResult
    data object NoPermission : BandDiscoveryResult
    data object NoBluetooth : BandDiscoveryResult
    data object BluetoothOff : BandDiscoveryResult
    data object ScanFailed : BandDiscoveryResult
}

interface BandDiscovery {
    suspend fun nearestMiBand6(): BandDiscoveryResult
}

data class BondedBandCandidate(val address: BandAddress, val name: String?)

interface BondedBandDiscovery {
    suspend fun alreadyBondedClassicDevices(): BandDiscoveryResult
}
