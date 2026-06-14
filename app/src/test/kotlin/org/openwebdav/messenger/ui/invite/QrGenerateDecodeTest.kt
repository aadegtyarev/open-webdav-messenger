package org.openwebdav.messenger.ui.invite

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Stack-spec test for QR generation (`ui-chat-surface` plan Stack expectations: ZXing core generate /
 * `qr_generate_then_decode_recovers_invite`). Encodes an `owdm1:` invite string to a ZXing `BitMatrix`
 * (the same `QrEncoder.encode` the screen uses), then decodes the matrix back with `MultiFormatReader` and
 * asserts the SAME string is recovered — verifying the ZXing generate/parse contract against the library.
 * Pure JVM (zxing `core` is pure Java, no Android Bitmap, no native `.so`).
 * Source: <https://zxing.github.io/zxing/apidocs/com/google/zxing/MultiFormatWriter.html>
 */
class QrGenerateDecodeTest {
    /** qr_generate_then_decode_recovers_invite. */
    @Test
    fun qr_generate_then_decode_recovers_invite() {
        // An owdm1-shaped string (the QR only carries the string; its decode is the codec's job, tested
        // separately). Obvious fake values (SC21).
        val invite = "owdm1:AbCdEf-0123456789_aBcDeFgHiJkLmNoPqRsTuVwXyZ"

        val matrix = QrEncoder.encode(invite, size = 512)
        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height) { i -> if (matrix.get(i % width, i / width)) BLACK else WHITE }

        val source = RGBLuminanceSource(width, height, pixels)
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))
        val decoded = MultiFormatReader().decode(bitmap, hints)

        assertEquals(invite, decoded.text)
    }

    private companion object {
        const val BLACK = 0xFF000000.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
    }
}
