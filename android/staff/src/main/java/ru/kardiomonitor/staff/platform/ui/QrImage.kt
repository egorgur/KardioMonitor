package ru.kardiomonitor.staff.platform.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

object QrImage {
    fun create(contents: String, size: Int): ImageBitmap {
        return Bitmap.createBitmap(pixels(contents, size), size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
    }

    internal fun pixels(contents: String, size: Int): IntArray {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "US-ASCII",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 2,
        )
        val matrix = QRCodeWriter().encode(contents, BarcodeFormat.QR_CODE, size, size, hints)
        return IntArray(size * size) { index ->
            if (matrix[index % size, index / size]) BLACK else WHITE
        }
    }

    private const val BLACK = 0xFF101816.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
}
