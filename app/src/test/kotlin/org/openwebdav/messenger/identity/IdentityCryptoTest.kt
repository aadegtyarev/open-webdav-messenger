package org.openwebdav.messenger.identity

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openwebdav.messenger.crypto.Aead
import org.openwebdav.messenger.crypto.OpenResult

/**
 * JVM unit + interaction tests for the identity substrate, run against real lazysodium-java
 * (system libsodium) in `./gradlew test`. These cover keygen, key agreement, sealed box, signing,
 * and the fingerprint — the behaviors in `docs/features/identity_plan.md` test plan.
 */
class IdentityCryptoTest {
    private val identity = IdentityTestSupport.identityCrypto()
    private val header = byteArrayOf(0x4F, 0x57, 0x44, 0x4D, 0x01, 0x00, 0x00, 0x00)

    // identity_generation_two_distinct_keypairs — generating an identity yields an Ed25519
    // (pk32/sk64) and an X25519 (pk32/sk32) keypair that are distinct.
    @Test
    fun identity_generation_two_distinct_keypairs() {
        val id = identity.generateIdentity()
        assertEquals(Identity.SIGN_PUB_BYTES, id.copySignPublic().size)
        assertEquals(Identity.SIGN_SEC_BYTES, id.copySignSecret().size)
        assertEquals(Identity.BOX_PUB_BYTES, id.copyBoxPublic().size)
        assertEquals(Identity.BOX_SEC_BYTES, id.copyBoxSecret().size)
        // The signing and box public keys are different material (two independent keypairs).
        assertFalse(id.copySignPublic().contentEquals(id.copyBoxPublic()))
        // Two generated identities differ (fresh keys each time).
        val other = identity.generateIdentity()
        assertFalse(id.copySignPublic().contentEquals(other.copySignPublic()))
        assertFalse(id.copyBoxPublic().contentEquals(other.copyBoxPublic()))
    }

    // dh_derives_same_chatkey_both_sides — A(sec, B.pub) and B(sec, A.pub) yield identical ChatKeys;
    // a message sealed with one opens with the other via the existing AEAD.
    @Test
    fun dh_derives_same_chatkey_both_sides() {
        val a = identity.generateIdentity()
        val b = identity.generateIdentity()
        val keyA = identity.agreeChatKey(a.copyBoxSecret(), b.copyBoxPublic())
        val keyB = identity.agreeChatKey(b.copyBoxSecret(), a.copyBoxPublic())
        assertArrayEquals(keyA.export(), keyB.export())

        // AEAD round-trip A→B with the agreed key, through the existing crypto substrate.
        val aead = Aead(IdentityTestSupport.native())
        val plaintext = "remote private chat message".toByteArray()
        val blob = aead.seal(keyA, header, plaintext)
        val opened = aead.open(keyB, header, blob)
        assertTrue(opened is OpenResult.Opened)
        assertArrayEquals(plaintext, (opened as OpenResult.Opened).bytes)
    }

    // agree_chatkey_symmetric_cross_device — interaction: two simulated identities, both agreeChatKey
    // paths produce the same key and an AEAD message round-trips A→B.
    @Test
    fun agree_chatkey_symmetric_cross_device() {
        val a = identity.generateIdentity()
        val b = identity.generateIdentity()
        val keyA = identity.agreeChatKey(a.copyBoxSecret(), b.copyBoxPublic())
        val keyB = identity.agreeChatKey(b.copyBoxSecret(), a.copyBoxPublic())
        assertArrayEquals(keyA.export(), keyB.export())
        // A third party cannot derive the same key from its own secret + A's public.
        val c = identity.generateIdentity()
        val keyC = identity.agreeChatKey(c.copyBoxSecret(), a.copyBoxPublic())
        assertFalse(keyA.export().contentEquals(keyC.export()))
    }

    // sealed_box_roundtrip — seal to a recipient public key, open with that recipient's keypair.
    @Test
    fun sealed_box_roundtrip() {
        val recipient = identity.generateIdentity()
        val plaintext = "new disk password for rotation".toByteArray()
        val sealed = identity.seal(plaintext, recipient.copyBoxPublic())
        val opened = identity.openSealed(sealed, recipient)
        assertTrue(opened is SealedResult.Opened)
        assertArrayEquals(plaintext, (opened as SealedResult.Opened).bytes)
    }

    // sealed_box_wrong_recipient_rejected / sealed_wrong_recipient_is_typed_rejection — opening a
    // sealed blob with a different keypair → Rejected (typed, no crash, no partial plaintext).
    @Test
    fun sealed_box_wrong_recipient_rejected() {
        val recipient = identity.generateIdentity()
        val wrong = identity.generateIdentity()
        val sealed = identity.seal("secret".toByteArray(), recipient.copyBoxPublic())
        assertEquals(SealedResult.Rejected, identity.openSealed(sealed, wrong))
        // A tampered sealed blob is also a typed rejection.
        sealed[sealed.size / 2] = (sealed[sealed.size / 2] + 1).toByte()
        assertEquals(SealedResult.Rejected, identity.openSealed(sealed, recipient))
        // A truncated blob is a rejection, not a bounds error.
        assertEquals(SealedResult.Rejected, identity.openSealed(ByteArray(4), recipient))
    }

    // sign_verify_detached_roundtrip — a signature over a message verifies true against signer pk.
    @Test
    fun sign_verify_detached_roundtrip() {
        val signer = identity.generateIdentity()
        val message = "directory entry to authenticate".toByteArray()
        val sig = identity.sign(message, signer.copySignSecret())
        assertEquals(Identity.SIGN_SEC_BYTES, signer.copySignSecret().size)
        assertEquals(64, sig.size)
        assertTrue(identity.verify(sig, message, signer.copySignPublic()))
    }

    // signature_tamper_or_wrong_key_rejected / verify_failure_drops_entry — a modified message, or
    // verification against a different public key, returns false (caller drops the entry).
    @Test
    fun signature_tamper_or_wrong_key_rejected() {
        val signer = identity.generateIdentity()
        val other = identity.generateIdentity()
        val message = "authentic".toByteArray()
        val sig = identity.sign(message, signer.copySignSecret())

        // Wrong signer key → false.
        assertFalse(identity.verify(sig, message, other.copySignPublic()))
        // Tampered message → false.
        val tampered = "authentiC".toByteArray()
        assertFalse(identity.verify(sig, tampered, signer.copySignPublic()))
        // Tampered signature → false.
        val badSig = sig.copyOf()
        badSig[0] = (badSig[0] + 1).toByte()
        assertFalse(identity.verify(badSig, message, signer.copySignPublic()))
    }

    // fingerprint_deterministic_and_symmetric — fingerprint(A,B) == fingerprint(B,A), stable across
    // calls, and differs for a different key pair.
    @Test
    fun fingerprint_deterministic_and_symmetric() {
        val a = identity.generateIdentity().publicIdentity()
        val b = identity.generateIdentity().publicIdentity()

        val ab = identity.fingerprint(a, b)
        val ba = identity.fingerprint(b, a)
        // Symmetric: identical regardless of order / device.
        assertArrayEquals(ab, ba)
        // Deterministic across calls.
        assertArrayEquals(ab, identity.fingerprint(a, b))
        assertEquals(IdentityCrypto.FINGERPRINT_BYTES, ab.size)

        // Different counterparty → different fingerprint.
        val c = identity.generateIdentity().publicIdentity()
        assertFalse(ab.contentEquals(identity.fingerprint(a, c)))
    }

    // generate_identity_zeroizes_keygen_source_arrays — the raw secret-key arrays returned by the native
    // keygen are copied into Identity (defensive copy), then the ORIGINAL source arrays are wiped, so no
    // redundant copy of the secret keys lingers on the heap (decision 10 fix 4 / Security constraints).
    @Test
    fun generate_identity_zeroizes_keygen_source_arrays() {
        val capturing = CapturingKeypairNative(IdentityTestSupport.native())
        val crypto = IdentityCrypto(capturing)

        val id = crypto.generateIdentity()

        // The source arrays handed back by signKeypair()/boxKeypair() are zeroized after generation.
        assertArrayEquals(ByteArray(Identity.SIGN_SEC_BYTES), capturing.signSecret!!)
        assertArrayEquals(ByteArray(Identity.SIGN_PUB_BYTES), capturing.signPublic!!)
        assertArrayEquals(ByteArray(Identity.BOX_SEC_BYTES), capturing.boxSecret!!)
        assertArrayEquals(ByteArray(Identity.BOX_PUB_BYTES), capturing.boxPublic!!)

        // The Identity still holds its own (non-zero) defensive copies — wiping the source did not
        // corrupt the identity.
        assertFalse(id.copySignSecret().contentEquals(ByteArray(Identity.SIGN_SEC_BYTES)))
        assertFalse(id.copyBoxSecret().contentEquals(ByteArray(Identity.BOX_SEC_BYTES)))
    }
}

/**
 * A [org.openwebdav.messenger.crypto.NativeCrypto] decorator that captures the exact arrays returned by
 * [signKeypair] / [boxKeypair], so a test can assert [IdentityCrypto.generateIdentity] zeroized those
 * source arrays after copying them into the [Identity]. Delegates every other op to [delegate].
 */
private class CapturingKeypairNative(
    private val delegate: org.openwebdav.messenger.crypto.NativeCrypto,
) : org.openwebdav.messenger.crypto.NativeCrypto by delegate {
    var signPublic: ByteArray? = null
    var signSecret: ByteArray? = null
    var boxPublic: ByteArray? = null
    var boxSecret: ByteArray? = null

    override fun signKeypair(): org.openwebdav.messenger.crypto.SignKeyPair {
        val kp = delegate.signKeypair()
        signPublic = kp.publicKey
        signSecret = kp.secretKey
        return kp
    }

    override fun boxKeypair(): org.openwebdav.messenger.crypto.BoxKeyPair {
        val kp = delegate.boxKeypair()
        boxPublic = kp.publicKey
        boxSecret = kp.secretKey
        return kp
    }
}
