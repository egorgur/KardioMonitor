package ru.kardiomonitor.staff.platform.db

import android.content.ContentValues
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.kardiomonitor.staff.features.bandcheck.data.SqliteBandCheckLog
import ru.kardiomonitor.staff.features.bandcheck.domain.BandCheck
import ru.kardiomonitor.staff.features.bandcheck.domain.CheckOutcome
import ru.kardiomonitor.staff.features.bandcheck.domain.TestedFirmware
import ru.kardiomonitor.staff.features.inventory.data.SqliteBandInventory
import ru.kardiomonitor.staff.features.inventory.domain.AuthKey
import ru.kardiomonitor.staff.features.inventory.domain.BandAddress
import ru.kardiomonitor.staff.features.inventory.domain.BandModel
import ru.kardiomonitor.staff.shared.BandProtocol
import ru.kardiomonitor.staff.features.inventory.domain.ClinicBand
import ru.kardiomonitor.staff.features.inventory.domain.KeyOrigin
import ru.kardiomonitor.staff.features.issuance.data.SqliteIssuanceLog
import ru.kardiomonitor.staff.platform.keystore.SecretBox
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class StaffProtocolPersistenceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val NAME = "staff-protocol-test.db"
    private val address = BandAddress.of("E7:19:F3:6C:04:9E")
    private val at = Instant.parse("2026-11-12T10:00:00Z")
    private val key = AuthKey.of("0123456789abcdef0123456789abcdef")

    @Before
    fun reset() { context.deleteDatabase(NAME) }

    @After
    fun clean() { context.deleteDatabase(NAME) }

    @Test
    fun band9InventoryCheckAndIssuanceSurviveReopen() = runBlocking {
        StaffDatabase(context, NAME).use { database ->
            SqliteBandInventory(database, TestSecretBox).save(
                ClinicBand(address, key, null, BandModel("Xiaomi Smart Band 9"), null, KeyOrigin.Manual, at, false,
                    BandProtocol.XiaomiBand9Spp),
            )
            SqliteBandCheckLog(database).save(
                BandCheck(address.value, at, CheckOutcome.Warning(listOf("Только чтение")),
                    TestedFirmware("2.3.98"), null, BandProtocol.XiaomiBand9Spp, 0, false),
            )
            SqliteIssuanceLog(database).openOrCurrent(address.value, null, at, BandProtocol.XiaomiBand9Spp)
        }
        StaffDatabase(context, NAME).use { database ->
            val inventory = SqliteBandInventory(database, TestSecretBox)
            assertEquals(BandProtocol.XiaomiBand9Spp, inventory.summaryByAddress(address)?.protocol)
            val band = requireNotNull(inventory.byAddress(address))
            assertEquals(key, band.key)
            assertEquals(BandProtocol.XiaomiBand9Spp, band.protocol)
            val check = requireNotNull(SqliteBandCheckLog(database).latestOf(address.value))
            assertEquals(BandProtocol.XiaomiBand9Spp, check.protocol)
            assertEquals(0, check.heartRateIntervalMinutes)
            assertEquals(false, check.heartRateDisabled)
            assertEquals(null, check.battery)
            val log = SqliteIssuanceLog(database)
            assertEquals(BandProtocol.XiaomiBand9Spp, log.history().single().protocol)
            assertTrue(log.returned(address.value, at.plusSeconds(1)))
            assertFalse(log.history().single().isOpen)
            assertEquals(BandProtocol.XiaomiBand9Spp, log.history().single().protocol)
        }
    }

    @Test
    fun unknownStoredProtocolNeverBecomesBand6() = runBlocking {
        StaffDatabase(context, NAME).use { database ->
            val inventory = SqliteBandInventory(database, TestSecretBox)
            inventory.save(ClinicBand(address, key, null, BandModel("Mi Smart Band 6"), null,
                KeyOrigin.Manual, at, false))
            database.writableDatabase.update("clinic_bands", ContentValues().apply { put("protocol", "future-protocol") },
                "address = ?", arrayOf(address.value))
            assertEquals(BandProtocol.Unknown, inventory.byAddress(address)?.protocol)
            assertEquals(BandProtocol.Unknown, inventory.summaryByAddress(address)?.protocol)
            val checks = SqliteBandCheckLog(database)
            checks.save(BandCheck(address.value, at, CheckOutcome.Ready, null, null))
            database.writableDatabase.update("band_checks", ContentValues().apply { put("protocol", "future-protocol") },
                "address = ?", arrayOf(address.value))
            assertEquals(BandProtocol.Unknown, checks.latestOf(address.value)?.protocol)
            val issuances = SqliteIssuanceLog(database)
            issuances.openOrCurrent(address.value, null, at, BandProtocol.MiBand6Ble)
            database.writableDatabase.update("issuances", ContentValues().apply { put("protocol", "future-protocol") },
                "address = ?", arrayOf(address.value))
            assertEquals(BandProtocol.Unknown, issuances.current(address.value)?.protocol)
            assertEquals(BandProtocol.Unknown, issuances.history().single().protocol)
            checks.save(BandCheck(address.value, at, CheckOutcome.Warning(listOf("Ограничения")), null, null))
            assertTrue(checks.latestOf(address.value)?.outcome is CheckOutcome.Warning)
        }
    }
}

/** Synthetic storage fixture, deliberately separate from the production Keystore implementation. */
private object TestSecretBox : SecretBox {
    override fun seal(plainText: ByteArray): ByteArray = plainText.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
    override fun open(sealed: ByteArray): ByteArray = seal(sealed)
}
