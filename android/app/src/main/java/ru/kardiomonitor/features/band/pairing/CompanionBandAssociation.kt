package ru.kardiomonitor.features.band.pairing

import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.IntentSender
import android.os.Build
import android.os.Handler
import android.os.Looper
import ru.kardiomonitor.features.band.api.BandAssociation
import ru.kardiomonitor.features.band.domain.BandAddress

/** Обычная подтверждаемая пациентом CDM-ассоциация, выбранная ADR-0013. */
class CompanionBandAssociation(context: Context) : BandAssociation {
    private val manager = context.getSystemService(CompanionDeviceManager::class.java)

    override fun isAssociated(address: BandAddress): Boolean {
        val deviceManager = manager ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                deviceManager.myAssociations.any {
                    it.deviceMacAddress?.toString()?.equals(address.value, ignoreCase = true) == true
                }
            } else {
                @Suppress("DEPRECATION")
                deviceManager.associations.any { it.equals(address.value, ignoreCase = true) }
            }
        } catch (_: SecurityException) {
            false
        }
    }

    fun request(
        address: BandAddress,
        onChooser: (IntentSender) -> Unit,
        onFailure: (String) -> Unit,
    ) {
        val filter = BluetoothDeviceFilter.Builder()
            .setAddress(address.value)
            .build()
        val request = AssociationRequest.Builder()
            .addDeviceFilter(filter)
            .setSingleDevice(true)
            .build()
        val deviceManager = manager
        if (deviceManager == null) {
            onFailure("системная ассоциация браслета недоступна на этом устройстве")
            return
        }
        try {
            deviceManager.associate(request, callback(onChooser, onFailure), Handler(Looper.getMainLooper()))
        } catch (_: SecurityException) {
            onFailure("нет разрешения на системную ассоциацию браслета")
        }
    }

    private fun callback(
        onChooser: (IntentSender) -> Unit,
        onFailure: (String) -> Unit,
    ): CompanionDeviceManager.Callback = object : CompanionDeviceManager.Callback() {
        @Deprecated("Android 12 callback")
        override fun onDeviceFound(chooserLauncher: IntentSender) = onChooser(chooserLauncher)

        override fun onAssociationPending(intentSender: IntentSender) = onChooser(intentSender)

        override fun onAssociationCreated(associationInfo: android.companion.AssociationInfo) = Unit

        override fun onFailure(error: CharSequence?) {
            onFailure(error?.toString() ?: "браслет не найден")
        }
    }
}
