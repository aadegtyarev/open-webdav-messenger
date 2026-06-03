package org.openwebdav.messenger.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AEAD seal/open unit tests (JVM, real libsodium via lazysodium-java) — the per-plan crypto suite
 * implementing `docs/protocol/webdav-layout.md` §5.1.
 */
class AeadTest {
    private val aead = CryptoTestSupport.aead()
    private val header = byteArrayOf(0x4F, 0x57, 0x44, 0x4D, 0x01, 0x00, 0x00, 0x00)
    private val key = CryptoTestSupport.fixedKey()

    @Test
    fun seal_open_roundtrip() {
        val plaintext = "hello, шифр".toByteArray()
        val blob = aead.seal(key, header, plaintext)
        val opened = aead.open(key, header, blob)
        assertTrue(opened is OpenResult.Opened)
        assertArrayEquals(plaintext, (opened as OpenResult.Opened).bytes)
    }

    @Test
    fun seal_open_roundtrip_empty_plaintext() {
        // §5.1: empty plaintext → minimum valid blob = 24-byte nonce + 16-byte tag = 40 bytes.
        val blob = aead.seal(key, header, ByteArray(0))
        assertEquals(Aead.MIN_BLOB_SIZE, blob.size)
        val opened = aead.open(key, header, blob)
        assertTrue(opened is OpenResult.Opened)
        assertEquals(0, (opened as OpenResult.Opened).bytes.size)
    }

    @Test
    fun header_is_bound_as_aad() {
        // Flipping any one of the 8 header bytes before open breaks the Poly1305 tag → Rejected.
        val plaintext = "aad-bound".toByteArray()
        val blob = aead.seal(key, header, plaintext)
        for (i in header.indices) {
            val tampered = header.copyOf()
            tampered[i] = (tampered[i] + 1).toByte()
            assertEquals("header byte $i must be AAD-bound", OpenResult.Rejected, aead.open(key, tampered, blob))
        }
    }

    @Test
    fun ciphertext_tamper_fails() {
        val blob = aead.seal(key, header, "tamper-me".toByteArray())
        // Flip a byte inside the ciphertext/tag region (anything past the 24-byte nonce).
        val tampered = blob.copyOf()
        tampered[Aead.NONCE_BYTES] = (tampered[Aead.NONCE_BYTES] + 1).toByte()
        assertEquals(OpenResult.Rejected, aead.open(key, header, tampered))
    }

    @Test
    fun nonce_tamper_fails() {
        val blob = aead.seal(key, header, "nonce".toByteArray())
        val tampered = blob.copyOf()
        tampered[0] = (tampered[0] + 1).toByte()
        assertEquals(OpenResult.Rejected, aead.open(key, header, tampered))
    }

    @Test
    fun wrong_key_fails_open() {
        val blob = aead.seal(key, header, "secret".toByteArray())
        val wrong = CryptoTestSupport.fixedKey(seed = 9)
        // Not a crash, not garbage plaintext — a typed Rejected.
        assertEquals(OpenResult.Rejected, aead.open(wrong, header, blob))
    }

    @Test
    fun nonce_is_random_per_seal() {
        val plaintext = "same plaintext".toByteArray()
        val a = aead.seal(key, header, plaintext)
        val b = aead.seal(key, header, plaintext)
        // Two seals of identical plaintext differ — distinct 24-byte nonces (content-addressing, §2).
        assertFalse(a.contentEquals(b))
        val nonceA = a.copyOfRange(0, Aead.NONCE_BYTES)
        val nonceB = b.copyOfRange(0, Aead.NONCE_BYTES)
        assertFalse(nonceA.contentEquals(nonceB))
        // Both still open to the same plaintext.
        assertArrayEquals(plaintext, (aead.open(key, header, a) as OpenResult.Opened).bytes)
        assertArrayEquals(plaintext, (aead.open(key, header, b) as OpenResult.Opened).bytes)
    }

    @Test
    fun truncated_blob_is_rejected() {
        // Anything shorter than nonce(24)+tag(16)=40 is a reject, NOT an index/bounds crash (§5.1).
        for (len in intArrayOf(0, 1, 23, 24, 39)) {
            assertEquals("len=$len", OpenResult.Rejected, aead.open(key, header, ByteArray(len)))
        }
    }
}
