package ru.kardiomonitor.staff.app

import android.content.Context
import ru.kardiomonitor.staff.features.bandcheck.check.CheckBand
import ru.kardiomonitor.staff.features.bandcheck.data.SqliteBandCheckLog
import ru.kardiomonitor.staff.features.inventory.bandlist.ObserveBandList
import ru.kardiomonitor.staff.features.bandcheck.api.LastCheckAccess
import ru.kardiomonitor.staff.features.inventory.api.InventoryAccess
import ru.kardiomonitor.staff.features.inventory.data.SqliteBandInventory
import ru.kardiomonitor.staff.features.inventory.labeling.LabelBand
import ru.kardiomonitor.staff.features.inventory.manualentry.AddBandManually
import ru.kardiomonitor.staff.features.issuance.data.SqliteIssuanceLog
import ru.kardiomonitor.staff.features.issuance.issuancelog.LoadIssuanceHistory
import ru.kardiomonitor.staff.features.issuance.issuancelog.ReturnBand
import ru.kardiomonitor.staff.features.issuance.qrdisplay.PrepareIssuance
import ru.kardiomonitor.staff.features.issuance.qrdisplay.ConfirmIssuance
import ru.kardiomonitor.staff.platform.band.MiBandInspector
import ru.kardiomonitor.staff.platform.band.BleBandDiscovery
import ru.kardiomonitor.staff.platform.db.StaffDatabase
import ru.kardiomonitor.staff.platform.keystore.KeystoreSecretBox
import java.time.Clock

/** Композиционный корень утилиты. Зависимости создаются вручную и живут в процессе приложения. */
class AppContainer(context: Context) {
    val applicationContext: Context = context.applicationContext
    private val clock = Clock.systemUTC()
    private val database = StaffDatabase(applicationContext)
    private val inventory = SqliteBandInventory(database, KeystoreSecretBox())
    private val inventoryAccess = InventoryAccess(inventory)
    private val checkLog = SqliteBandCheckLog(database)
    private val lastCheck = LastCheckAccess(checkLog)
    private val issuanceLog = SqliteIssuanceLog(database)

    val observeBandList = ObserveBandList(inventory)
    val bandDiscovery = BleBandDiscovery(applicationContext)
    val classicBandDiscovery = ru.kardiomonitor.staff.platform.band.ClassicBandDiscovery(applicationContext)
    val addBandManually = AddBandManually(inventory, clock)
    val labelBand = LabelBand(inventory)
    val checkBand = CheckBand(
        inventoryAccess,
        ru.kardiomonitor.staff.platform.band.ProtocolBandInspector(
            MiBandInspector(applicationContext),
            ru.kardiomonitor.staff.platform.band.XiaomiBand9Inspector(applicationContext),
        ),
        checkLog,
        clock,
    )
    val prepareIssuance = PrepareIssuance(inventoryAccess, lastCheck, issuanceLog, clock)
    val confirmIssuance = ConfirmIssuance(issuanceLog, clock, prepareIssuance)
    val returnBand = ReturnBand(issuanceLog, clock)
    val loadIssuanceHistory = LoadIssuanceHistory(issuanceLog)
}