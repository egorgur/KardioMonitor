package ru.kardiomonitor.staff.platform.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.kardiomonitor.staff.features.bandcheck.data.SqliteBandCheckLog
import ru.kardiomonitor.staff.features.inventory.data.SqliteBandInventory
import ru.kardiomonitor.staff.features.inventory.domain.AuthKey
import ru.kardiomonitor.staff.features.inventory.domain.BandAddress
import ru.kardiomonitor.staff.features.inventory.domain.BandModel
import ru.kardiomonitor.staff.features.inventory.domain.ClinicBand
import ru.kardiomonitor.staff.features.inventory.domain.KeyOrigin
import ru.kardiomonitor.staff.features.inventory.domain.StoredKeyUnavailable
import ru.kardiomonitor.staff.features.issuance.data.SqliteIssuanceLog
import ru.kardiomonitor.staff.features.issuance.domain.PairingCode
import ru.kardiomonitor.staff.platform.keystore.KeystoreSecretBox
import ru.kardiomonitor.staff.shared.BandProtocol
import java.security.KeyStore
import java.time.Instant
import java.util.UUID

/** Только синтетические записи, отдельная БД и отдельный alias; рабочие данные не затрагиваются. */
@RunWith(AndroidJUnit4::class)
class KeystoreInventoryCompatibilityTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val id = UUID.randomUUID().toString()
    private val name = "staff-keystore-$id.db"
    private val alias = "staff-keystore-$id"
    private val address = BandAddress.of("AA:BB:CC:DD:EE:01")
    private val key = AuthKey.of("00112233445566778899aabbccddeeff")
    private val at = Instant.parse("2026-11-12T10:00:00Z")

    @After
    fun cleanOnlyTestStorage() {
        context.deleteDatabase(name)
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }

    @Test
    fun bothProtocolsDecryptAfterRepositoryAndKeystoreRecreation() = runBlocking {
        listOf(BandProtocol.MiBand6Ble, BandProtocol.XiaomiBand9Spp).forEach { protocol ->
            val model = if (protocol == BandProtocol.MiBand6Ble) "Mi Smart Band 6" else "M2345B1"
            StaffDatabase(context, name).use { database ->
                SqliteBandInventory(database, KeystoreSecretBox(alias)).save(
                    ClinicBand(address, key, null, BandModel(model), null, KeyOrigin.Manual, at, false, protocol),
                )
            }
            StaffDatabase(context, name).use { database ->
                val band = requireNotNull(SqliteBandInventory(database, KeystoreSecretBox(alias)).byAddress(address))
                assertEquals(key, band.key)
                assertEquals(protocol, band.protocol)
                val sealed = sealedKey(database)
                key.useBytes { assertFalse(sealed.contentEquals(it)) }
                assertTrue(sealed.size >= 45)
            }
        }
    }

    @Test
    fun legacyVersionTwoMetadataAndCiphertextSurviveUpgradeWithoutReEncryption() = runBlocking {
        val sealed = key.useBytes(KeystoreSecretBox(alias)::seal)
        createLegacyVersionTwo(sealed)
        StaffDatabase(context, name).use { database ->
            val band = requireNotNull(SqliteBandInventory(database, KeystoreSecretBox(alias)).byAddress(address))
            assertEquals(key, band.key)
            assertEquals("Тест-01", band.label?.value)
            assertEquals(at, band.keyReceivedAt)
            assertEquals(KeyOrigin.Manual, band.keyOrigin)
            assertEquals(BandProtocol.MiBand6Ble, band.protocol)
            assertArrayEquals(sealed, sealedKey(database))
            assertEquals(BandProtocol.MiBand6Ble, SqliteBandCheckLog(database).latestOf(address.value)?.protocol)
            assertEquals(BandProtocol.MiBand6Ble, SqliteIssuanceLog(database).history().single().protocol)
            band.key.useHex { hex ->
                PairingCode.of(address.value, hex, band.protocol).useEncoded {
                    assertTrue(it.startsWith("kardio3;mi-band6-ble;"))
                }
            }
        }
        StaffDatabase(context, name).use { database ->
            assertEquals(key, SqliteBandInventory(database, KeystoreSecretBox(alias)).byAddress(address)?.key)
            assertArrayEquals(sealed, sealedKey(database))
        }
    }

    @Test
    fun lostKeystoreKeepsMetadataAndCiphertextForAdministrator() = runBlocking {
        StaffDatabase(context, name).use { database ->
            SqliteBandInventory(database, KeystoreSecretBox(alias)).save(
                ClinicBand(address, key, null, BandModel("M2345B1"), null, KeyOrigin.Manual, at, false,
                    BandProtocol.XiaomiBand9Spp),
            )
            val before = sealedKey(database)
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
            val reopened = SqliteBandInventory(database, KeystoreSecretBox(alias))
            assertEquals(BandProtocol.XiaomiBand9Spp, reopened.summaryByAddress(address)?.protocol)
            var refused = false
            try {
                reopened.byAddress(address)
            } catch (_: StoredKeyUnavailable) {
                refused = true
            }
            assertTrue(refused)
            assertArrayEquals(before, sealedKey(database))
            assertFalse(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias))
        }
    }

    private fun sealedKey(database: StaffDatabase): ByteArray = database.readableDatabase.query(
        "clinic_bands", arrayOf("key_sealed"), "address = ?", arrayOf(address.value), null, null, null,
    ).use { it.moveToFirst(); it.getBlob(0) }

    private fun createLegacyVersionTwo(sealed: ByteArray) {
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { database ->
            database.execSQL("CREATE TABLE clinic_bands (address TEXT PRIMARY KEY, key_sealed BLOB NOT NULL, " +
                "label TEXT, label_key TEXT, model TEXT NOT NULL, firmware TEXT, key_origin TEXT NOT NULL, " +
                "key_received_at INTEGER NOT NULL, in_cloud INTEGER NOT NULL)")
            database.execSQL("CREATE TABLE band_checks (id INTEGER PRIMARY KEY, address TEXT, checked_at INTEGER, " +
                "outcome TEXT, detail TEXT, firmware TEXT, battery_percent INTEGER)")
            database.execSQL("CREATE TABLE issuances (id INTEGER PRIMARY KEY, address TEXT, label TEXT, " +
                "issued_at INTEGER, returned_at INTEGER)")
            database.execSQL("INSERT INTO clinic_bands VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf(address.value, sealed, "Тест-01", "тест-01", "Mi Smart Band 6", "V1.0.6.10", "Manual", at.epochSecond, 0))
            database.execSQL("INSERT INTO band_checks VALUES (1, ?, ?, 'ready', NULL, 'V1.0.6.10', 80)",
                arrayOf(address.value, at.plusSeconds(1).epochSecond))
            database.execSQL("INSERT INTO issuances VALUES (1, ?, NULL, ?, NULL)", arrayOf(address.value, at.epochSecond))
            database.version = 2
        }
    }
}
