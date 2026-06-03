package org.openwebdav.messenger.identity

/**
 * The **publishable** half of a user [Identity]: the Ed25519 signing public key and the X25519 box
 * public key (each 32 bytes). These are the only identity bytes that may cross to the WebDAV disk —
 * a future directory entry exposes them; the matching secret keys stay Keystore-wrapped device-local
 * (`docs/architecture.md` decision 10 / Security constraints).
 *
 * Two distinct keys, NOT the single-Ed25519-converted-to-X25519 path (decision 10, libsodium's own
 * "using distinct keys for signing and for encryption is still highly recommended" —
 * https://doc.libsodium.org/advanced/ed25519-curve25519). The single-identity UX is preserved because
 * the verification [fingerprint][IdentityCrypto.fingerprint] is computed over BOTH public keys.
 *
 * @property signPub the 32-byte Ed25519 public key (verifies signatures by this identity).
 * @property boxPub the 32-byte X25519 public key (key agreement + sealed-box recipient).
 */
class PublicIdentity(signPub: ByteArray, boxPub: ByteArray) {
    val signPub: ByteArray = signPub.copyOf()
    val boxPub: ByteArray = boxPub.copyOf()

    init {
        require(this.signPub.size == SIGN_PUB_BYTES) { "signPub must be $SIGN_PUB_BYTES bytes" }
        require(this.boxPub.size == BOX_PUB_BYTES) { "boxPub must be $BOX_PUB_BYTES bytes" }
    }

    /** Fresh copies of the two public keys — public bytes, safe to publish. */
    fun copySignPub(): ByteArray = signPub.copyOf()

    fun copyBoxPub(): ByteArray = boxPub.copyOf()

    override fun equals(other: Any?): Boolean =
        other is PublicIdentity && signPub.contentEquals(other.signPub) && boxPub.contentEquals(other.boxPub)

    override fun hashCode(): Int = 31 * signPub.contentHashCode() + boxPub.contentHashCode()

    companion object {
        /** Ed25519 public key length (`crypto_sign_PUBLICKEYBYTES`). */
        const val SIGN_PUB_BYTES = 32

        /** X25519 public key length (`crypto_box_PUBLICKEYBYTES`). */
        const val BOX_PUB_BYTES = 32
    }
}
