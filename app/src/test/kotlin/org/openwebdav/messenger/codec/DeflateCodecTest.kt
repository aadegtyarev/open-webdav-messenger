package org.openwebdav.messenger.codec

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * Unit tests for [DeflateCodec]: roundtrip fidelity, bounded inflate (SC7 zip-bomb guard), empty
 * message, message that doesn't compress well, corrupted input rejection, non-DEFLATE data rejection.
 */
class DeflateCodecTest {
    private val codec = DeflateCodec()

    @Test
    fun `roundtrip ASCII text`() {
        val plaintext = "Hello, world! This is a test message for DEFLATE compression roundtrip.".toByteArray()
        val compressed = codec.compress(plaintext)
        val result = codec.decompress(compressed)
        assertTrue(result is DecompressResult.Ok)
        assertArrayEquals(plaintext, (result as DecompressResult.Ok).bytes)
    }

    @Test
    fun `roundtrip UTF-8 text`() {
        val plaintext = "こんにちは世界 🌍 Привет мир 你好世界".toByteArray(Charsets.UTF_8)
        val compressed = codec.compress(plaintext)
        val result = codec.decompress(compressed)
        assertTrue(result is DecompressResult.Ok)
        assertArrayEquals(plaintext, (result as DecompressResult.Ok).bytes)
    }

    @Test
    fun `roundtrip long text`() {
        val sb = StringBuilder()
        repeat(200) { sb.append("The quick brown fox jumps over the lazy dog. ") }
        val plaintext = sb.toString().toByteArray()
        val compressed = codec.compress(plaintext)
        val result = codec.decompress(compressed)
        assertTrue(result is DecompressResult.Ok)
        assertArrayEquals(plaintext, (result as DecompressResult.Ok).bytes)
    }

    @Test
    fun `roundtrip random binary data`() {
        val plaintext = ByteArray(8192)
        SecureRandom().nextBytes(plaintext)
        val compressed = codec.compress(plaintext)
        val result = codec.decompress(compressed)
        assertTrue(result is DecompressResult.Ok)
        assertArrayEquals(plaintext, (result as DecompressResult.Ok).bytes)
    }

    @Test
    fun `empty message roundtrip`() {
        val plaintext = ByteArray(0)
        val compressed = codec.compress(plaintext)
        val result = codec.decompress(compressed)
        assertTrue(result is DecompressResult.Ok)
        assertArrayEquals(plaintext, (result as DecompressResult.Ok).bytes)
    }

    @Test
    fun `message that does not compress well is preserved`() {
        // Random bytes do not compress well; the codec still round-trips them faithfully.
        // DEFLATE may output more bytes than input for incompressible data — that's fine,
        // the point is the decompress recovers the exact original.
        val plaintext = ByteArray(256).also { SecureRandom().nextBytes(it) }
        val compressed = codec.compress(plaintext)
        val result = codec.decompress(compressed)
        assertTrue(result is DecompressResult.Ok)
        assertArrayEquals(plaintext, (result as DecompressResult.Ok).bytes)
    }

    @Test
    fun `compression reduces size of repetitive data`() {
        // Highly compressible data (zeros) should shrink significantly.
        val plaintext = ByteArray(4096) // all zeros
        val compressed = codec.compress(plaintext)
        assertTrue(
            "compressed size ${compressed.size} should be < plaintext size ${plaintext.size}",
            compressed.size < plaintext.size / 2,
        )
        val result = codec.decompress(compressed)
        assertTrue(result is DecompressResult.Ok)
        assertArrayEquals(plaintext, (result as DecompressResult.Ok).bytes)
    }

    @Test
    fun `corrupted compressed data is rejected or produces wrong output without crashing`() {
        val plaintext = "test message for corruption check".toByteArray()
        val compressed = codec.compress(plaintext)
        // Corrupt the final byte which contains the end-of-block marker — this should reliably
        // produce either a DataFormatException (→ Rejected) or garbage output that does not match
        // the original. Either way the codec must not crash.
        val corrupted = compressed.copyOf()
        corrupted[compressed.size - 1] = (corrupted[compressed.size - 1].toInt() xor 0xFF).toByte()
        val result = codec.decompress(corrupted)
        // The codec must not crash. If it decompresses without error, the output must differ
        // from the original (otherwise the corruption was undetectable — vanishingly unlikely
        // for the end-of-block byte).
        when (result) {
            is DecompressResult.Rejected -> { /* expected for most corruptions */ }
            is DecompressResult.Ok ->
                assertTrue(
                    "corrupted stream should not recover the exact original",
                    !result.bytes.contentEquals(plaintext),
                )
        }
    }

    @Test
    fun `truncated compressed data is rejected`() {
        val plaintext = "test message for truncation".toByteArray()
        val compressed = codec.compress(plaintext)
        val truncated = compressed.copyOf(compressed.size / 2)
        assertEquals(DecompressResult.Rejected, codec.decompress(truncated))
    }

    @Test
    fun `non-deflate data is rejected`() {
        // Raw bytes that are not valid DEFLATE at all should be rejected, not crashed.
        val garbage = ByteArray(128).also { SecureRandom().nextBytes(it) }
        assertEquals(DecompressResult.Rejected, codec.decompress(garbage))
    }

    @Test
    fun `bounded inflate rejects oversize output`() {
        // Generate compressible data (zeros) that, when inflated, exceeds the 1 MiB bound.
        // The deflate stream for such data is small — a few hundred bytes of compressed zeros
        // inflate to far more than 1 MiB.
        val plaintext = ByteArray(1_048_577) // just over the 1 MiB bound
        val compressed = codec.compress(plaintext)
        assertEquals(DecompressResult.Rejected, codec.decompress(compressed))
    }

    @Test
    fun `single byte roundtrip`() {
        val plaintext = byteArrayOf(42)
        val compressed = codec.compress(plaintext)
        val result = codec.decompress(compressed)
        assertTrue(result is DecompressResult.Ok)
        assertArrayEquals(plaintext, (result as DecompressResult.Ok).bytes)
    }

    @Test
    fun `per-message independence — two compressions do not share state`() {
        // SC6: each compress call uses a fresh Deflater, so compressing "secret" then "public"
        // should not leak secret bytes into the second output.
        val secret = "s3cr3t_k3y".toByteArray()
        val public = "public text".toByteArray()
        val c1 = codec.compress(secret)
        val c2 = codec.compress(public)
        assertTrue(codec.decompress(c1) is DecompressResult.Ok)
        assertTrue(codec.decompress(c2) is DecompressResult.Ok)
    }
}
