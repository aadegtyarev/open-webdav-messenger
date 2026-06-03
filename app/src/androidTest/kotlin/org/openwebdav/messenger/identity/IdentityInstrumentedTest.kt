package org.openwebdav.messenger.identity

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.openwebdav.messenger.crypto.Aead
import org.openwebdav.messenger.crypto.CryptoFactory
import org.openwebdav.messenger.crypto.OpenResult

/**
 * Instrumented identity tests — run on the connected device (5c3ff0, arm64) via
 * `./gradlew connectedAndroidTest`. These exercise the REAL lazysodium-android native `.so` public-key
 * paths (crypto_box / crypto_sign / sealed-box / generichash) through [IdentityFactory]
 * (`docs/stack-notes.md` Crypto: a missing ABI = UnsatisfiedLinkError, only catchable on a device).
 */
@RunWith(AndroidJUnit4::class)
class IdentityInstrumentedTest {
    private val factory = IdentityFactory()

    // native_pubkey_paths_load — crypto_box / crypto_sign / sealed-box / generichash run on the device
    // ABI with no UnsatisfiedLinkError. Source: https://github.com/terl/lazysodium-android
    @Test
    fun native_pubkey_paths_load() {
        val identity = factory.identityCrypto()
        val a = identity.generateIdentity()
        val b = identity.generateIdentity()

        // crypto_box_keypair + crypto_box_beforenm + KDF (generichash) all execute natively.
        val key = identity.agreeChatKey(a.copyBoxSecret(), b.copyBoxPublic())
        assertEquals(org.openwebdav.messenger.crypto.ChatKey.KEY_BYTES, key.export().size)

        // crypto_sign_detached / verify execute natively.
        val sig = identity.sign("m".toByteArray(), a.copySignSecret())
        assertTrue(identity.verify(sig, "m".toByteArray(), a.copySignPublic()))

        // crypto_box_seal / seal_open execute natively.
        val sealed = identity.seal("p".toByteArray(), b.copyBoxPublic())
        assertTrue(identity.openSealed(sealed, b) is SealedResult.Opened)
    }

    // dh_and_seal_native_roundtrip — real lazysodium-android key-agreement + sealed-box round-trip,
    // and the agreed ChatKey feeds the existing AEAD on-device.
    @Test
    fun dh_and_seal_native_roundtrip() {
        val identity = factory.identityCrypto()
        val a = identity.generateIdentity()
        val b = identity.generateIdentity()

        val keyA = identity.agreeChatKey(a.copyBoxSecret(), b.copyBoxPublic())
        val keyB = identity.agreeChatKey(b.copyBoxSecret(), a.copyBoxPublic())
        assertArrayEquals(keyA.export(), keyB.export())

        // The agreed key drives the existing XChaCha20-Poly1305 AEAD on-device.
        val aead: Aead = CryptoFactory().aead()
        val header = byteArrayOf(0x4F, 0x57, 0x44, 0x4D, 0x01, 0x00, 0x00, 0x00)
        val plaintext = "on-device DH message".toByteArray()
        val blob = aead.seal(keyA, header, plaintext)
        val opened = aead.open(keyB, header, blob)
        assertTrue(opened is OpenResult.Opened)
        assertArrayEquals(plaintext, (opened as OpenResult.Opened).bytes)

        // Sealed-box round-trip + wrong-recipient typed rejection on-device.
        val sealed = identity.seal(plaintext, b.copyBoxPublic())
        assertTrue(identity.openSealed(sealed, b) is SealedResult.Opened)
        assertEquals(SealedResult.Rejected, identity.openSealed(sealed, a))
    }

    // fingerprint_symmetric_on_device — the BLAKE2b safety number is symmetric on the device backend.
    @Test
    fun fingerprint_symmetric_on_device() {
        val identity = factory.identityCrypto()
        val a = identity.generateIdentity().publicIdentity()
        val b = identity.generateIdentity().publicIdentity()
        assertArrayEquals(identity.fingerprint(a, b), identity.fingerprint(b, a))
        assertNotNull(identity.fingerprint(a, b))
    }

    // Sanity: a tampered signature is rejected on-device too (hard reject, no best-effort).
    @Test
    fun verify_rejects_tamper_on_device() {
        val identity = factory.identityCrypto()
        val signer = identity.generateIdentity()
        val sig = identity.sign("authentic".toByteArray(), signer.copySignSecret())
        assertFalse(identity.verify(sig, "tampered".toByteArray(), signer.copySignPublic()))
    }
}
