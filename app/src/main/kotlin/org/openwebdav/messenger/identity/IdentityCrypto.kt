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
 *  - [deriveRemoteChatKey] — X25519 DH → **chat-id-bound** KDF → [ChatKey] (the **fourth** key source
 *    for the existing AEAD; distinct per chat-id, the production per-chat derivation).
 *  - [agreeChatKey] — the bare pairwise DH → KDF → [ChatKey], superseded-for-chat-keys by
 *    [deriveRemoteChatKey] (its output is not chat-id-bound; kept as a tested primitive only).
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
     * The **bare pairwise** DH primitive: `crypto_box_beforenm` shared secret → keyed-BLAKE2b over a
     * fixed (non-chat-id-bound) context `owdm/x25519-chatkey/v1` → [ChatKey] (decision 10).
     *
     * **Superseded for chat-key use by [deriveRemoteChatKey].** Its output is the same for *every* chat
     * between a given identity pair (the v1 context carries no chat-id), so it MUST NOT be used directly
     * as a per-chat AEAD key — two distinct chats between the same pair would share one key (blocker D10).
     * Production derives per-chat keys via [deriveRemoteChatKey]; this remains as the tested bare
     * pairwise primitive only (no production caller). A compile-time misuse guard — a distinct return
     * type for the bare pairwise output — is a deferred hardening (it would touch this primitive's tests).
     *
     * The raw DH output is NEVER used directly as the AEAD key — it is run through a keyed BLAKE2b KDF
     * (https://doc.libsodium.org/public-key_cryptography/authenticated_encryption ; the "do not feed
     * the raw shared secret to the AEAD" rule). Symmetric: A(myBoxSk, B.boxPk) == B(myBoxSk, A.boxPk),
     * so both devices derive the same key. The shared-secret buffer is zeroized before return.
     */
    fun agreeChatKey(
        myBoxSecret: ByteArray,
        peerBoxPublic: ByteArray,
    ): ChatKey = deriveFromDh(myBoxSecret, peerBoxPublic, CHATKEY_KDF_CONTEXT)

    /**
     * The production per-chat DH derivation (the D10 fix): `crypto_box_beforenm` shared secret →
     * keyed-BLAKE2b over a **chat-id-bound** message → 32-byte [ChatKey], distinct per [chatId].
     *
     * The keyed-hash **message** is `"owdm/x25519-chatkey/v2" ‖ 0x1F ‖ chatId` — a new v2 domain-
     * separation context, an explicit `0x1F` Unit-Separator, then the chat-id's full bytes (byte-
     * identical in spirit to `KeySources.knownKey`'s `KNOWN_KEY_CONSTANT ‖ 0x1F ‖ chatId`). Because the
     * fixed v2 context never contains `0x1F`, `(ctx, chatId)` has exactly one parse — no two chat-ids
     * collide by concatenation (https://doc.libsodium.org/hashing/generic_hashing). The DH shared secret
     * is the keyed-BLAKE2b **key**, so the raw DH output is NEVER the AEAD key
     * (https://doc.libsodium.org/public-key_cryptography/authenticated_encryption).
     *
     * **No per-chat salt:** the load-bearing property is that both members derive the SAME key from
     * **public inputs only** (their own box secret + the peer's box public + the public chat-id), with no
     * secret exchanged over any channel. A random salt would itself be a shared secret to distribute,
     * defeating that property; the chat-id is the public, mutually-known differentiator (the same
     * reasoning `KeySources.saltForChat` applies). Symmetric across the two parties for a fixed chat-id.
     * The shared-secret buffer and the derived intermediate are zeroized before return.
     */
    fun deriveRemoteChatKey(
        myBoxSecret: ByteArray,
        peerBoxPublic: ByteArray,
        chatId: String,
    ): ChatKey {
        // context ‖ 0x1F ‖ chatId — fixed leading context, explicit separator, trailing variable chat-id.
        val message = REMOTE_CHATKEY_KDF_CONTEXT + byteArrayOf(DOMAIN_SEPARATOR) + chatId.toByteArray(Charsets.UTF_8)
        return deriveFromDh(myBoxSecret, peerBoxPublic, message)
    }

    /**
     * Shared DH→KDF body: `crypto_box_beforenm` → keyed BLAKE2b over [contextMessage], keyed by the DH
     * shared secret, to 32 bytes (turns the non-uniform DH output into a uniformly-distributed AEAD key).
     * The shared secret and the derived intermediate are zeroized in `finally`.
     */
    private fun deriveFromDh(
        myBoxSecret: ByteArray,
        peerBoxPublic: ByteArray,
        contextMessage: ByteArray,
    ): ChatKey {
        val shared = native.boxBeforeNm(peerBoxPublic, myBoxSecret)
        try {
            val derived = native.keyedHash(contextMessage, shared, ChatKey.KEY_BYTES)
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
            // Wipe both copies pulled out of the identity. boxSec is secret; boxPub is wiped too for
            // uniformity (same discipline as generateIdentity), matching the sibling secret-wipe contract.
            // Both are local — never returned — so this changes no caller-observable behaviour.
            boxSec.fill(0)
            boxPub.fill(0)
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
        /**
         * Domain-separation context for the **bare pairwise** [agreeChatKey] (no chat-id). Fixed v1 —
         * carries no per-chat differentiator, which is exactly why its output is not a per-chat key.
         */
        private val CHATKEY_KDF_CONTEXT: ByteArray = "owdm/x25519-chatkey/v1".toByteArray(Charsets.UTF_8)

        /**
         * Domain-separation context for the **chat-id-bound** [deriveRemoteChatKey] (the D10 fix). The
         * **v2** bump marks the new chat-id-bound purpose explicitly (the "distinct versioned context per
         * derivation purpose" rule, https://doc.libsodium.org/hashing/generic_hashing). Nothing is
         * persisted under v1 (the v1 [agreeChatKey] has no production caller), so v2 is a pure additive
         * change with no on-disk migration. The chat-id is appended after a [DOMAIN_SEPARATOR].
         */
        private val REMOTE_CHATKEY_KDF_CONTEXT: ByteArray = "owdm/x25519-chatkey/v2".toByteArray(Charsets.UTF_8)

        /**
         * ASCII Unit Separator (0x1F) between the fixed v2 context and the variable-length chat-id in
         * [deriveRemoteChatKey] — the same canonicalization idiom as `KeySources.knownKey`, so no two
         * (context, chatId) pairs can collide by concatenation.
         */
        private const val DOMAIN_SEPARATOR: Byte = 0x1F

        /** Domain-separation context for a single party's fingerprint. */
        private val PER_PARTY_CONTEXT: ByteArray = "owdm/identity-fp/party/v1".toByteArray(Charsets.UTF_8)

        /** Domain-separation context for the combined (symmetric) fingerprint. */
        private val FINGERPRINT_CONTEXT: ByteArray = "owdm/identity-fp/combined/v1".toByteArray(Charsets.UTF_8)

        /** Fingerprint digest length — 32 bytes (`crypto_generichash_BYTES`, well above MIN 16). */
        const val FINGERPRINT_BYTES = 32
    }
}
