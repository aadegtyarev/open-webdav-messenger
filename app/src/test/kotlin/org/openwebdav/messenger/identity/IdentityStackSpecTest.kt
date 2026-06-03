package org.openwebdav.messenger.identity

import com.goterl.lazysodium.interfaces.Box
import com.goterl.lazysodium.interfaces.Sign
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openwebdav.messenger.crypto.ChatKey

/**
 * Stack-spec tests: each asserts a cited rule from `docs/stack-notes.md` → Crypto "Public-key
 * primitives" / `docs/architecture.md` decision 10, verifying against the rule (not the coder's own
 * mapping). Each test references its source URL.
 */
class IdentityStackSpecTest {
    private val native = IdentityTestSupport.native()
    private val identity = IdentityTestSupport.identityCrypto()

    // chatkey_is_kdf_of_shared_secret_not_raw — the derived ChatKey is NOT the raw crypto_box_beforenm
    // output; a KDF was applied. "do not feed the raw shared secret to the AEAD; derive from it."
    // Source: https://doc.libsodium.org/public-key_cryptography/authenticated_encryption
    @Test
    fun chatkey_is_kdf_of_shared_secret_not_raw() {
        val a = identity.generateIdentity()
        val b = identity.generateIdentity()
        val rawShared = native.boxBeforeNm(b.copyBoxPublic(), a.copyBoxSecret())
        assertEquals(Box.BEFORENMBYTES, rawShared.size)
        val chatKey = identity.agreeChatKey(a.copyBoxSecret(), b.copyBoxPublic())
        // Both are 32 bytes, but the derived key must NOT equal the raw DH output (a KDF was run).
        assertEquals(ChatKey.KEY_BYTES, chatKey.export().size)
        assertFalse("ChatKey must be KDF'd, not the raw shared secret", chatKey.export().contentEquals(rawShared))
    }

    // box_nonce_is_24_bytes — crypto_box uses a 24-byte nonce (sealed box hides it internally per
    // message; we assert the libsodium constant the substrate relies on, and that sealed-box overhead
    // is the fixed SEALBYTES = 48). The per-seal freshness is shown by two seals of identical
    // plaintext producing different ciphertext (ephemeral sender key + nonce).
    // Source: https://doc.libsodium.org/public-key_cryptography/sealed_boxes
    @Test
    fun box_nonce_is_24_bytes_and_seal_is_fresh() {
        assertEquals(24, Box.NONCEBYTES)
        assertEquals(48, Box.SEALBYTES)
        val recipient = identity.generateIdentity()
        val plaintext = "same".toByteArray()
        val s1 = identity.seal(plaintext, recipient.copyBoxPublic())
        val s2 = identity.seal(plaintext, recipient.copyBoxPublic())
        // Overhead is exactly SEALBYTES.
        assertEquals(plaintext.size + Box.SEALBYTES, s1.size)
        // Two seals of identical plaintext differ (fresh ephemeral keypair/nonce each time).
        assertFalse("sealed box must be fresh per call", s1.contentEquals(s2))
        // Both still open to the original.
        assertEquals(SealedResult.Opened(plaintext), identity.openSealed(s1, recipient))
        assertEquals(SealedResult.Opened(plaintext), identity.openSealed(s2, recipient))
    }

    // ed25519_verify_rejects_on_failure — verify returns false on a bad signature (libsodium -1 = hard
    // reject), never true-by-default. Source:
    // https://doc.libsodium.org/public-key_cryptography/public-key_signatures
    @Test
    fun ed25519_verify_rejects_on_failure() {
        val signer = identity.generateIdentity()
        val message = "m".toByteArray()
        val sig = identity.sign(message, signer.copySignSecret())
        assertTrue(identity.verify(sig, message, signer.copySignPublic()))
        // An all-zero signature must be rejected (not accidentally accepted).
        assertFalse(identity.verify(ByteArray(Sign.BYTES), message, signer.copySignPublic()))
        // A wrong-length signature is rejected without throwing.
        assertFalse(identity.verify(ByteArray(10), message, signer.copySignPublic()))
        // A wrong-length public key is rejected without throwing.
        assertFalse(identity.verify(sig, message, ByteArray(10)))
    }

    // sign_and_dh_keys_are_independent — the Ed25519 and X25519 keypairs are separately generated, not
    // one converted from the other (the chosen distinct-keys design, decision 10).
    // Source: https://doc.libsodium.org/advanced/ed25519-curve25519
    @Test
    fun sign_and_dh_keys_are_independent() {
        val id = identity.generateIdentity()
        assertEquals(Sign.PUBLICKEYBYTES, id.copySignPublic().size)
        assertEquals(Box.PUBLICKEYBYTES, id.copyBoxPublic().size)
        // The signing and box public keys are simply different material.
        assertFalse(id.copySignPublic().contentEquals(id.copyBoxPublic()))
        // Stronger: if the box key had been DERIVED from the sign key via the ed25519->curve25519
        // conversion (the path decision 10 rejects), the box public key would EQUAL convert(signPub).
        // The substrate generates them independently, so it must NOT equal that conversion.
        val converted = IdentityTestSupport.convertSignPubToBoxPub(id.copySignPublic())
        assertFalse(
            "box key must be independently generated, NOT convert(signPub)",
            id.copyBoxPublic().contentEquals(converted),
        )
    }

    // identity_persists_across_load (Keystore-independent half) — the serialized identity format
    // round-trips losslessly, so a store-then-load reconstructs the SAME keys. The Keystore-bound
    // store/load is covered in the instrumented suite.
    @Test
    fun identity_serialize_roundtrip_preserves_keys() {
        val id = identity.generateIdentity()
        val serialized = Identity.serialize(id)
        assertEquals(Identity.SERIALIZED_BYTES, serialized.size)
        val restored = Identity.deserialize(serialized)
        assertNotNull(restored)
        assertArrayEquals(id.copySignPublic(), restored!!.copySignPublic())
        assertArrayEquals(id.copySignSecret(), restored.copySignSecret())
        assertArrayEquals(id.copyBoxPublic(), restored.copyBoxPublic())
        assertArrayEquals(id.copyBoxSecret(), restored.copyBoxSecret())
        // A wrong-length blob deserializes to null (typed rejection, not a crash).
        assertNull(Identity.deserialize(ByteArray(10)))
    }
}
