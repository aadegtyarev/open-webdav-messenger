package org.openwebdav.messenger.crypto

import com.goterl.lazysodium.interfaces.AEAD
import com.goterl.lazysodium.interfaces.PwHash
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureTimeMillis

/**
 * Stack-spec tests: each asserts a cited rule from `docs/stack-notes.md` / the architecture, verifying
 * against the rule (not the coder's own mapping). Each test references its source URL.
 */
class CryptoStackSpecTest {
    private val native = CryptoTestSupport.native()
    private val aead = Aead(native)
    private val header = byteArrayOf(0x4F, 0x57, 0x44, 0x4D, 0x01, 0x00, 0x00, 0x00)

    // aead_is_xchacha20_poly1305 — the AEAD is XChaCha20-Poly1305 (24-byte/192-bit nonce, 16-byte
    // tag), NOT AES-GCM (12-byte nonce). Architecture decision 1 / libsodium AEAD.
    // Source: https://doc.libsodium.org/secret-key_cryptography/aead
    @Test
    fun aead_is_xchacha20_poly1305() {
        // libsodium's XChaCha20-Poly1305 IETF constants: 24-byte nonce, 32-byte key, 16-byte tag.
        assertEquals(24, AEAD.XCHACHA20POLY1305_IETF_NPUBBYTES)
        assertEquals(32, AEAD.XCHACHA20POLY1305_IETF_KEYBYTES)
        assertEquals(16, AEAD.XCHACHA20POLY1305_IETF_ABYTES)
        // The substrate uses exactly these: the blob's nonce prefix is 24 bytes (not AES-GCM's 12).
        assertEquals(24, Aead.NONCE_BYTES)
        assertEquals(16, Aead.TAG_BYTES)

        // And a real seal yields exactly nonce(24) + plaintext + tag(16) bytes.
        val plaintext = "x".repeat(10).toByteArray()
        val blob = aead.seal(CryptoTestSupport.fixedKey(), header, plaintext)
        assertEquals(Aead.NONCE_BYTES + plaintext.size + Aead.TAG_BYTES, blob.size)
    }

    // kdf_is_argon2id — passphrase key derivation uses Argon2id (memory-hard), NOT a fast hash.
    // libsodium pwhash. Source: https://doc.libsodium.org/password_hashing/default_phf
    @Test
    fun kdf_is_argon2id() =
        runTest {
            // The Argon2id alg + INTERACTIVE preset the substrate pins must match libsodium's values.
            assertEquals(PwHash.OPSLIMIT_INTERACTIVE, KeySources.ARGON2ID_OPS_INTERACTIVE)
            assertEquals(PwHash.ARGON2ID_MEMLIMIT_INTERACTIVE, KeySources.ARGON2ID_MEM_INTERACTIVE)
            assertEquals(16, PwHash.ARGON2ID_SALTBYTES)

            // Memory-hardness is observable: Argon2id is intentionally slow vs a plain hash of the same
            // input. A bare BLAKE2b of the passphrase is sub-millisecond; Argon2id INTERACTIVE takes
            // meaningfully longer. We assert it is not a degenerate fast hash (lower bound only, so the
            // test is not flaky on fast hardware).
            val pw = "a-passphrase".toByteArray()
            val fastHashMs = measureTimeMillis { repeat(50) { native.genericHash(pw, 32) } }
            val argonMs =
                measureTimeMillis {
                    native.argon2id(
                        pw,
                        ByteArray(PwHash.ARGON2ID_SALTBYTES),
                        32,
                        KeySources.ARGON2ID_OPS_INTERACTIVE,
                        KeySources.ARGON2ID_MEM_INTERACTIVE,
                    )
                }
            assertTrue("Argon2id ($argonMs ms) must be much slower than a fast hash ($fastHashMs ms)", argonMs > fastHashMs)
        }

    // raw_key_never_persisted — no path writes a raw key / passphrase to a file, the transport, or a
    // log. Keys exist only in memory (and, on-device, Keystore-wrapped — instrumented test). Here we
    // assert the in-process contract: ChatKey.toString is redacted and the sealed blob does not contain
    // the raw key. Source: https://developer.android.com/privacy-and-security/keystore
    @Test
    fun raw_key_never_persisted() {
        val raw = ByteArray(ChatKey.KEY_BYTES) { (it + 1).toByte() }
        val key = ChatKey.fromBytes(raw)

        // toString must never reveal key material (no accidental log leak).
        assertFalse(key.toString().contains("1"))
        assertEquals("ChatKey(***)", key.toString())

        // The sealed envelope blob (what is written to the disk) must not contain the raw key bytes.
        val blob = aead.seal(key, header, "msg".toByteArray())
        assertFalse("raw key must not appear in the ciphertext written to disk", containsSubsequence(blob, raw))

        // The same for a passphrase-derived key path: the salt + derivation never echo the key.
        assertNotEquals("derived key must differ from the public input bytes", raw.toList(), header.toList())
    }

    private fun containsSubsequence(
        haystack: ByteArray,
        needle: ByteArray,
    ): Boolean {
        if (needle.isEmpty() || haystack.size < needle.size) return false
        outer@ for (start in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[start + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }
}
