package ru.kardiomonitor.staff.features.inventory.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class InventoryValuesTest {
    @Test
    fun `адрес нормализуется к верхнему регистру`() {
        assertEquals("E7:19:F3:6C:04:9E", BandAddress.of("e7:19:f3:6c:04:9e").value)
    }

    @Test
    fun `метка очищает края и ограничена двадцатью знаками`() {
        assertEquals("Б-07", BandLabel.of("  Б-07 ").value)
        assertThrows(IllegalArgumentException::class.java) { BandLabel.of("123456789012345678901") }
    }

    @Test
    fun `строковое представление ключа не раскрывает секрет`() {
        val key = AuthKey.of("00112233445566778899aabbccddeeff")
        assertFalse(key.toString().contains("001122"))
        assertEquals("AuthKey(<hidden>)", key.toString())
    }

    @Test
    fun `поддерживается только семейство Mi Band 6`() {
        assertEquals(true, BandModel("Mi Smart Band 6").isSupported)
        assertEquals(true, BandModel("Xiaomi Smart Band 6").isSupported)
        assertFalse(BandModel("Generic Band 6").isSupported)
        assertFalse(BandModel("Mi Smart Band 60").isSupported)
        assertFalse(BandModel("Fake Mi Smart Band 6 Clone").isSupported)
        assertFalse(BandModel("Mi Smart Band 6 NFC").isSupported)
    }

    @Test
    fun `ключ метки нормализует эквивалентный Unicode`() {
        assertEquals(BandLabel.of("Café").comparisonKey, BandLabel.of("Cafe\u0301").comparisonKey)
        assertEquals(BandLabel.of("Б-07").comparisonKey, BandLabel.of("б-07").comparisonKey)
    }
}
