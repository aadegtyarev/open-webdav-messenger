package org.openwebdav.messenger.directory

import org.openwebdav.messenger.identity.PublicIdentity

/**
 * A **verified** directory record (`docs/protocol/webdav-layout.md` §10.3): a member's display-name
 * and their two **public** identity keys, returned by [DirectoryService] only after the entry's
 * Ed25519 signature verified against the carried [signingPublicKey] and the AEAD-open with the
 * community key succeeded.
 *
 * The public-key bytes are exactly the `identity` substrate's `publicIdentity()` shape (32 bytes each),
 * so a downstream DH / QR-safety-number consumer takes them directly — but binding [displayName] to a
 * human is the deferred QR-safety-number UI's job, NOT this record's (§10.3 self-published trust).
 *
 * @property displayName raw UTF-8 display-name as published (no rendering / normalization — UI concern).
 * @property signingPublicKey the author's 32-byte Ed25519 signing public key (the verified grouping key).
 * @property boxPublicKey the author's 32-byte X25519 box public key (DH / sealed-box recipient).
 */
class DirectoryEntry(
    val displayName: String,
    signingPublicKey: ByteArray,
    boxPublicKey: ByteArray,
) {
    val signingPublicKey: ByteArray = signingPublicKey.copyOf()
    val boxPublicKey: ByteArray = boxPublicKey.copyOf()

    init {
        require(this.signingPublicKey.size == DirectoryFormat.SIGNING_PUBKEY_BYTES) {
            "signingPublicKey must be ${DirectoryFormat.SIGNING_PUBKEY_BYTES} bytes"
        }
        require(this.boxPublicKey.size == DirectoryFormat.BOX_PUBKEY_BYTES) {
            "boxPublicKey must be ${DirectoryFormat.BOX_PUBKEY_BYTES} bytes"
        }
    }

    /** Fresh copies of the two public keys (public bytes — safe to hand to a consumer). */
    fun copySigningPublicKey(): ByteArray = signingPublicKey.copyOf()

    fun copyBoxPublicKey(): ByteArray = boxPublicKey.copyOf()

    /** The publishable [PublicIdentity] (same two-key shape `publicIdentity()` yields). */
    fun toPublicIdentity(): PublicIdentity = PublicIdentity(signingPublicKey, boxPublicKey)

    override fun equals(other: Any?): Boolean =
        other is DirectoryEntry &&
            displayName == other.displayName &&
            signingPublicKey.contentEquals(other.signingPublicKey) &&
            boxPublicKey.contentEquals(other.boxPublicKey)

    override fun hashCode(): Int {
        var result = displayName.hashCode()
        result = 31 * result + signingPublicKey.contentHashCode()
        result = 31 * result + boxPublicKey.contentHashCode()
        return result
    }

    override fun toString(): String = "DirectoryEntry(displayName=$displayName, keys=***)"
}
