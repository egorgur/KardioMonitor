package ru.kardiomonitor.features.band.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.test.InstrumentationTestCase
import ru.kardiomonitor.features.band.domain.AuthKey
import ru.kardiomonitor.features.band.domain.Band
import ru.kardiomonitor.features.band.domain.BandAddress
import ru.kardiomonitor.features.band.domain.BandCredentials
import ru.kardiomonitor.features.band.domain.BandProtocol
import ru.kardiomonitor.platform.keystore.AndroidKeystoreSecretBox
import java.security.KeyStore
import java.time.Instant
import java.util.UUID

/** Только синтетические данные, отдельные preferences и alias; пациентская база не открывается. */
@Suppress("DEPRECATION")
class BandBindingStorageTest : InstrumentationTestCase() {
    private val suffix = UUID.randomUUID().toString()
    private val alias = "kardiomonitor.test.binding.$suffix"
    private lateinit var isolated: Context

    override fun setUp() {
        super.setUp()
        val target = instrumentation.targetContext
        isolated = object : ContextWrapper(target) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                target.getSharedPreferences("test-binding-$suffix-$name", mode)
        }
    }

    override fun tearDown() {
        try {
            instrumentation.targetContext.deleteSharedPreferences("test-binding-$suffix-band")
            instrumentation.targetContext.deleteSharedPreferences("test-binding-$suffix-band_settings")
            keyStore().deleteEntry(alias)
        } finally {
            super.tearDown()
        }
    }

    fun testBand9RestoresProtocolAndEncryptedKeyWithoutLegacyFallback() {
        val band = Band.paired(credentials(BandProtocol.XiaomiBand9Spp), Instant.parse("2026-11-13T01:00:00Z"))
        repository().save(band)
        val stored = preferences()
        assertFalse(stored.contains("key"))
        assertFalse(stored.getString("key_sealed", null).orEmpty().contains(band.credentials.key.hex))
        assertEquals(band, repository().band.value)
        stored.edit().putString("protocol", "unknown-protocol").commit()
        assertNull(repository().band.value)
        assertTrue(stored.contains("key_sealed"))
    }

    fun testLegacyBand6MigratesWithoutChangingProtocolOrLosingKey() {
        val legacy = credentials(BandProtocol.MiBand6Ble)
        preferences().edit().putString("mac", legacy.address.value).putString("key", legacy.key.hex).commit()
        val loaded = repository().band.value
        assertEquals(legacy, loaded?.credentials)
        assertNull(loaded?.pairedAt)
        assertFalse(preferences().contains("key"))
        assertTrue(preferences().contains("key_sealed"))
        assertEquals(loaded, repository().band.value)
    }

    fun testMissingKeystoreKeyDoesNotGenerateReplacementOrRestoreBand6() {
        repository().save(Band.paired(credentials(BandProtocol.XiaomiBand9Spp), Instant.EPOCH))
        val sealed = preferences().getString("key_sealed", null)
        keyStore().deleteEntry(alias)
        assertNull(repository().band.value)
        assertFalse(keyStore().containsAlias(alias))
        assertEquals(sealed, preferences().getString("key_sealed", null))
    }

    private fun repository() = SharedPreferencesBandRepository(isolated, AndroidKeystoreSecretBox(alias))
    private fun preferences() = isolated.getSharedPreferences("band", Context.MODE_PRIVATE)
    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun credentials(protocol: BandProtocol) = BandCredentials(
        BandAddress("AA:BB:CC:DD:EE:FF"), AuthKey("00112233445566778899aabbccddeeff"), protocol,
    )
}
