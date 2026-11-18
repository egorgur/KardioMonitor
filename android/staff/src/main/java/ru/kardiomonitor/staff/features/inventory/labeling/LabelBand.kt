package ru.kardiomonitor.staff.features.inventory.labeling

import ru.kardiomonitor.staff.features.inventory.domain.BandAddress
import ru.kardiomonitor.staff.features.inventory.domain.BandInventory
import ru.kardiomonitor.staff.features.inventory.domain.BandLabel

sealed interface LabelBandResult {
    data object Saved : LabelBandResult
    data object BandMissing : LabelBandResult
    data object Invalid : LabelBandResult
    data object Duplicate : LabelBandResult
}

class LabelBand(private val inventory: BandInventory) {
    suspend operator fun invoke(address: BandAddress, text: String): LabelBandResult {
        val label = try {
            BandLabel.of(text)
        } catch (_: IllegalArgumentException) {
            return LabelBandResult.Invalid
        }
        if (inventory.summaryByAddress(address) == null) return LabelBandResult.BandMissing
        if (inventory.hasLabel(label, except = address)) return LabelBandResult.Duplicate
        return if (inventory.updateLabel(address, label)) LabelBandResult.Saved else LabelBandResult.BandMissing
    }
}
