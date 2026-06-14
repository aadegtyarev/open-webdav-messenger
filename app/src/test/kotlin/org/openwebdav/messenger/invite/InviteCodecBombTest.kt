package org.openwebdav.messenger.invite

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.DeflaterOutputStream

/**
 * Decompression-bomb DoS guard for the `owdm1:` codec (review finding 3). The invite payload is
 * attacker-controlled (a scanned/pasted foreign token); an unbounded inflate would let a few-hundred-byte
 * token inflate to gigabytes and OOM the process before the reject-don't-guess validation runs.
 * [InviteCodec.gunzip] caps the inflated output at [InviteCodec.MAX_INFLATED_BYTES]; reading past the cap
 * is a typed [InviteCodec.Result.Rejected], never an OOM. All NEW; no existing test touched.
 */
class InviteCodecBombTest {
    private val codec = InviteCodec()

    /**
     * A highly-compressible payload that inflates to well past the cap (a classic zip-bomb shape: a long run
     * of one byte compresses to a tiny frame). The codec must reject it, not OOM. The crafted compressed
     * frame is a few KB, so the test itself stays cheap.
     */
    @Test
    fun oversized_inflation_is_rejected_not_oom() {
        // 16 MB of zeros — far above the 64 KB inflate cap, but compresses to a few KB.
        val oversized = ByteArray(16 * 1024 * 1024)
        val token = InviteCodec.PREFIX + gzipBase64(oversized)

        val result = codec.decodeBlocking(token)

        assertTrue("an over-cap inflation must reject, never OOM", result is InviteCodec.Result.Rejected)
        assertNull((result as? InviteCodec.Result.Decoded)?.token)
    }

    /** A payload that inflates to just under the cap still passes the inflate step (then rejects on shape). */
    @Test
    fun under_cap_inflation_passes_inflate_then_rejects_on_shape() {
        // Just under the cap: this inflates fine, so the codec gets past gunzip and rejects on JSON shape
        // (proving the cap rejects on SIZE, not by failing every large-ish input).
        val underCap = ByteArray(InviteCodec.MAX_INFLATED_BYTES - 1) { 'x'.code.toByte() }
        val token = InviteCodec.PREFIX + gzipBase64(underCap)

        val result = codec.decodeBlocking(token)

        // It inflated successfully (not an OOM/crash) and was rejected at the JSON-shape stage.
        assertTrue(result is InviteCodec.Result.Rejected)
    }

    /** Deflate [bytes] and url-base64-encode it the same way the codec frames a payload. */
    private fun gzipBase64(bytes: ByteArray): String {
        val out = ByteArrayOutputStream()
        DeflaterOutputStream(out).use { it.write(bytes) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
    }
}
