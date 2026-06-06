package org.openwebdav.messenger.ui.invite

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * QR generation via pure-Java `com.google.zxing:core` (`ui-chat-surface` arch note Choice 5; stack-notes
 * QR-generate: "`MultiFormatWriter.encode(...) → BitMatrix` then draw the matrix yourself"). No native
 * `.so`, no Play Services. Encodes the `owdm1:` invite string into a [BitMatrix] (testable on the JVM) and
 * renders it into a Compose [ImageBitmap].
 *
 * Error correction is kept modest (`M`) with a small quiet-zone [MARGIN] so a few-hundred-char token stays
 * scannable on a phone screen (stack-notes QR-generate: "keep EC modest … so the symbol stays scannable").
 */
internal object QrEncoder {
    private const val MARGIN = 1
    private const val SET_BIT = Color.BLACK
    private const val UNSET_BIT = Color.WHITE

    /** Encode [contents] to a square QR [BitMatrix] of [size]×[size] modules-scaled pixels. */
    fun encode(
        contents: String,
        size: Int = 512,
    ): BitMatrix {
        val hints =
            mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to MARGIN,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            )
        return MultiFormatWriter().encode(contents, BarcodeFormat.QR_CODE, size, size, hints)
    }

    /** Render a [BitMatrix] into a Compose [ImageBitmap] (one black pixel per set module cell). */
    fun toImageBitmap(matrix: BitMatrix): ImageBitmap {
        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val offset = y * width
            for (x in 0 until width) {
                pixels[offset + x] = if (matrix.get(x, y)) SET_BIT else UNSET_BIT
            }
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap.asImageBitmap()
    }
}
