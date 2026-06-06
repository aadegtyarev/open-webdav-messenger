package org.openwebdav.messenger.crypto

import com.goterl.lazysodium.interfaces.AEAD
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A4 AEAD-size single-sourcing — doubles as the libsodium stack-spec test. The AEAD framing sizes used
 * across the §5.1 seal/open and the §10/§11 directory MUST equal the libsodium
 * `crypto_aead_xchacha20poly1305_ietf_*` constants (the value-home stays the libsodium-derived numbers,
 * NOT a re-invented literal), and `ChatKey.KEY_BYTES` MUST derive from the same home as the AEAD key
 * width. New test file.
 *
 * Source: libsodium XChaCha20-Poly1305 AEAD — nonce = 24, tag = 16, key = 32
 * (<https://doc.libsodium.org/secret-key_cryptography/aead>); the lazysodium constants
 * `AEAD.XCHACHA20POLY1305_IETF_NPUBBYTES` / `_ABYTES` / `_KEYBYTES` `LazySodiumCrypto` validates against.
 */
class AeadSizeConstantsTest {
    @Test
    fun aead_sizes_equal_the_libsodium_constants() {
        // The spec values (the documented XChaCha20-Poly1305 sizes).
        assertEquals(24, Aead.NONCE_BYTES)
        assertEquals(16, Aead.TAG_BYTES)
        assertEquals(32, Aead.KEY_BYTES)

        // The single source of truth: each framing size derives from the libsodium constant, so the
        // framing and the native call can never drift apart.
        assertEquals(AEAD.XCHACHA20POLY1305_IETF_NPUBBYTES, Aead.NONCE_BYTES)
        assertEquals(AEAD.XCHACHA20POLY1305_IETF_ABYTES, Aead.TAG_BYTES)
        assertEquals(AEAD.XCHACHA20POLY1305_IETF_KEYBYTES, Aead.KEY_BYTES)
    }

    @Test
    fun chat_key_width_is_single_sourced_from_the_aead_home() {
        // ChatKey.KEY_BYTES derives from Aead.KEY_BYTES (the libsodium-derived home) — the key width and
        // the AEAD key width are the same value by construction.
        assertEquals(Aead.KEY_BYTES, ChatKey.KEY_BYTES)
        assertEquals(AEAD.XCHACHA20POLY1305_IETF_KEYBYTES, ChatKey.KEY_BYTES)
        // MIN_BLOB_SIZE (nonce + tag) follows from the same home.
        assertEquals(Aead.NONCE_BYTES + Aead.TAG_BYTES, Aead.MIN_BLOB_SIZE)
    }
}
