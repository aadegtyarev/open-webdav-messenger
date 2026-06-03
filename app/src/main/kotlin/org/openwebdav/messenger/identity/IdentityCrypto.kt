package org.openwebdav.messenger.identity

import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.crypto.NativeCrypto

/**
 * The asymmetric / identity substrate (`docs/architecture.md` decision 10). Pure crypto over the
 * shared [NativeCrypto] — it produces and consumes opaque bytes and key handles, defines no on-disk
 * format, and publishes nothing. The **same** instance runs on lazysodium-java (JVM tests) and
 * lazysodium-android (app), since [NativeCrypto] abstracts the backend.
 *
 * Provides the public-key primitives the directory / private-chat / rotation features consume:
 *  - [generateIdentity] — two distinct keypairs (Ed25519 sign + X25519 box).
 *  - [agreeChatKey] — X25519 DH → KDF → [ChatKey] (the **fourth** key source for the existing AEAD).
 *  - [seal] / [openSealed] — anonymous sealed box (the rotation primitive; sender-unauthenticated).
 *  - [sign] / [verify] — Ed25519 detached signatures (hard reject on failure).
 *  - [fingerprint] — symmetric BLAKE2b safety number over both parties' public identities.
 */
class IdentityCrypto(private val native: NativeCrypto) {
    /**
     * Generate a fresh identity: an Ed25519 signing keypair AND a **separate** X25519 box keypair,
     * each generated independently on-device (decision 10 — NOT the ed25519→curve25519 conversion).
     */
    fun generateIdentity(): Identity {
        val sign = native.signKeypair()
        val box = native.boxKeypair()
        try {
            // Identity defensively copies each array (Identity.<init> → copyOf), so the originals
            // returned by the native keygen are now redundant secret material on the heap. Wipe them —
            // same zeroize discipline applied to the DH shared secret in agreeChatKey and to the
            // serialized blob in IdentityStore (Security constraints: secret bytes live no longer than
            // needed). Public-key bytes are wiped too for uniformity; they are not secret.
            return Identity(
                signPublic = sign.publicKey,
                signSecret = sign.secretKey,
                boxPublic = box.publicKey,
                boxSecret = box.secretKey,
            )
        } finally {
            sign.secretKey.fill(0)
            sign.publicKey.fill(0)
            box.secretKey.fill(0)
            box.publicKey.fill(0)
        }
    }

    /**
     * Derive the symmetric [ChatKey] shared with a peer from my X25519 secret key and the peer's
     * X25519 public key: `crypto_box_beforenm` shared secret → **KDF** → [ChatKey] (decision 10).
     *
     * The raw DH output is NEVER used directly as the AEAD key — it is run through a keyed BLAKE2b KDF
     * (https://doc.libsodium.org/public-key_cryptography/authenticated_encryption ; the "do not feed
     * the raw shared secret to the AEAD" rule). Symmetric: A(myBoxSk, B.boxPk) == B(myBoxSk, A.boxPk),
     * so both devices derive the same key. The shared-secret buffer is zeroized before return.
     */
    fun agreeChatKey(
        myBoxSecret: ByteArray,
        peerBoxPublic: ByteArray,
    ): ChatKey {
        val shared = native.boxBeforeNm(peerBoxPublic, myBoxSecret)
        try {
            // KDF: keyed BLAKE2b over a fixed context, keyed by the DH shared secret, to 32 bytes.
            // This turns the (non-uniform) DH output into a uniformly-distributed AEAD key.
            val derived = native.keyedHash(CHATKEY_KDF_CONTEXT, shared, ChatKey.KEY_BYTES)
            try {
                return ChatKey.fromBytes(derived)
            } finally {
                derived.fill(0)
            }
        } finally {
            shared.fill(0)
        }
    }

    /**
     * Anonymous sealed box: encrypt [plaintext] to [recipientBoxPublic] with an ephemeral internal
     * sender keypair (decision 10). Only the recipient's keypair opens it. **Sender-unauthenticated** —
     * a consumer relying on provenance (rotation) MUST add an Ed25519 [sign] over the payload.
     * Source: https://doc.libsodium.org/public-key_cryptography/sealed_boxes
     */
    fun seal(
        plaintext: ByteArray,
        recipientBoxPublic: ByteArray,
    ): ByteArray = native.boxSeal(plaintext, recipientBoxPublic)

    /**
     * Open a sealed [blob] with my X25519 keypair. Returns [SealedResult.Opened] with the exact
     * original bytes, or [SealedResult.Rejected] if it does not open under ([myBoxPublic], [myBoxSecret])
     * (wrong recipient / tampered / truncated) — typed rejection, never an exception.
     */
    fun openSealed(
        blob: ByteArray,
        myBoxPublic: ByteArray,
        myBoxSecret: ByteArray,
    ): SealedResult {
        val opened = native.boxSealOpen(blob, myBoxPublic, myBoxSecret) ?: return SealedResult.Rejected
        return SealedResult.Opened(opened)
    }

    /** Convenience overload: open with the recipient's [identity]. */
    fun openSealed(
        blob: ByteArray,
        identity: Identity,
    ): SealedResult {
        val boxPub = identity.copyBoxPublic()
        val boxSec = identity.copyBoxSecret()
        try {
            return openSealed(blob, boxPub, boxSec)
        } finally {
            boxSec.fill(0)
        }
    }

    /** Ed25519 detached signature (64 bytes) over [message] under [signSecret] (64). */
    fun sign(
        message: ByteArray,
        signSecret: ByteArray,
    ): ByteArray = native.signDetached(message, signSecret)

    /**
     * Verify an Ed25519 detached [signature] over [message] against [signerPublic]. Returns `true`
     * only on success; `false` on any failure (libsodium `-1`) — a **hard reject**, never best-effort
     * (decision 10). Source: https://doc.libsodium.org/public-key_cryptography/public-key_signatures
     */
    fun verify(
        signature: ByteArray,
        message: ByteArray,
        signerPublic: ByteArray,
    ): Boolean = native.signVerifyDetached(signature, message, signerPublic)

    /**
     * The symmetric verification fingerprint (safety number) over two public identities: BLAKE2b of
     * the two per-party fingerprints **sorted then concatenated** (decision 10 / Signal pattern —
     * https://signal.org/blog/safety-number-updates/). The sort makes the value identical on both
     * devices regardless of argument order / which device computes it. Returns the fingerprint
     * **bytes**; the human display format (digit grouping) is a later UI concern.
     *
     * Each party's per-party fingerprint binds BOTH of that party's public keys (signPub ‖ boxPub),
     * so the user verifies the whole identity in one value.
     */
    fun fingerprint(
        a: PublicIdentity,
        b: PublicIdentity,
    ): ByteArray {
        val fpA = perParty(a)
        val fpB = perParty(b)
        // Sort the two per-party fingerprints lexicographically, then concatenate — symmetry.
        val (first, second) = if (compareLex(fpA, fpB) <= 0) fpA to fpB else fpB to fpA
        val combined = ByteArray(first.size + second.size)
        first.copyInto(combined, 0)
        second.copyInto(combined, first.size)
        return native.genericHash(FINGERPRINT_CONTEXT + combined, FINGERPRINT_BYTES)
    }

    /** Per-party fingerprint: BLAKE2b over `context ‖ signPub(32) ‖ boxPub(32)`. */
    private fun perParty(p: PublicIdentity): ByteArray {
        val input = PER_PARTY_CONTEXT + p.copySignPub() + p.copyBoxPub()
        return native.genericHash(input, FINGERPRINT_BYTES)
    }

    private fun compareLex(
        x: ByteArray,
        y: ByteArray,
    ): Int {
        val n = minOf(x.size, y.size)
        for (i in 0 until n) {
            val c = (x[i].toInt() and 0xFF) - (y[i].toInt() and 0xFF)
            if (c != 0) return c
        }
        return x.size - y.size
    }

    companion object {
        /** Domain-separation context, keyed-hashed by the DH shared secret to derive the ChatKey. */
        private val CHATKEY_KDF_CONTEXT: ByteArray = "owdm/x25519-chatkey/v1".toByteArray(Charsets.UTF_8)

        /** Domain-separation context for a single party's fingerprint. */
        private val PER_PARTY_CONTEXT: ByteArray = "owdm/identity-fp/party/v1".toByteArray(Charsets.UTF_8)

        /** Domain-separation context for the combined (symmetric) fingerprint. */
        private val FINGERPRINT_CONTEXT: ByteArray = "owdm/identity-fp/combined/v1".toByteArray(Charsets.UTF_8)

        /** Fingerprint digest length — 32 bytes (`crypto_generichash_BYTES`, well above MIN 16). */
        const val FINGERPRINT_BYTES = 32
    }
}
