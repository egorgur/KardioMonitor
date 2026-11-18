package ru.kardiomonitor.staff.platform.qr

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Test
import ru.kardiomonitor.staff.platform.ui.QrImage

class QrImageTest {
    @Test
    fun `сгенерированный qr возвращает исходный код привязки`() {
        val contents = "kardio3;xiaomi-band9-spp;E719F36C049E;ASNFZ4mrze8BI0VniavN7w"
        val size = 512
        val source = RGBLuminanceSource(size, size, QrImage.pixels(contents, size))
        val decoded = QRCodeReader().decode(
            BinaryBitmap(HybridBinarizer(source)),
            mapOf(DecodeHintType.PURE_BARCODE to true),
        )

        assertEquals(contents, decoded.text)
    }
}