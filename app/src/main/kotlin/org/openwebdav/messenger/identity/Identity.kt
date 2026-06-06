package org.openwebdav.messenger.identity

/**
 * A user's full on-device **identity** (`docs/architecture.md` decision 10): an Ed25519 **signing**
 * keypair AND a separate X25519 **box** keypair. Two distinct keypairs (not the ed25519→curve25519
 * conversion path) per libsodium's recommendation.
 *
 * The **secret** keys ([signSecret] 64, [boxSecret] 32) are device-local secret material — same
 * discipline as chat keys: Keystore-wrapped, never written to the WebDAV disk, never logged (Security
 * constraints). The raw bytes live in memory only; [toString] is redacted and the secret keys are
 * reachable only via [copySignSecret] / [copyBoxSecret] (used by the in-process crypto + Keystore-wrap
 * layers). The **public** half is exposed via [publicIdentity].
 *
 * @property signPublic Ed25519 public key (32).
 * @property signSecret Ed25519 secret key (64) — embeds the seed and public key.
 * @property boxPublic X25519 public key (32).
 * @property boxSecret X25519 secret key (32).
 */
class Identity(
    signPublic: ByteArray,
    signSecret: ByteArray,
    boxPublic: ByteArray,
    boxSecret: ByteArray,
) {
    private val signPublic: ByteArray = signPublic.copyOf()
    private val signSecret: ByteArray = signSecret.copyOf()
    private val boxPublic: ByteArray = boxPublic.copyOf()
    private val boxSecret: ByteArray = boxSecret.copyOf()

    init {
        require(this.signPublic.size == SIGN_PUB_BYTES) { "signPublic must be $SIGN_PUB_BYTES bytes" }
        require(this.signSecret.size == SIGN_SEC_BYTES) { "signSecret must be $SIGN_SEC_BYTES bytes" }
        require(this.boxPublic.size == BOX_PUB_BYTES) { "boxPublic must be $BOX_PUB_BYTES bytes" }
        require(this.boxSecret.size == BOX_SEC_BYTES) { "boxSecret must be $BOX_SEC_BYTES bytes" }
    }

    /** The publishable public half (Ed25519 pk + X25519 pk). */
    fun publicIdentity(): PublicIdentity = PublicIdentity(signPublic, boxPublic)

    /** Fresh copies of the public keys (publishable). */
    fun copySignPublic(): ByteArray = signPublic.copyOf()

    fun copyBoxPublic(): ByteArray = boxPublic.copyOf()

    /**
     * Fresh copies of the **secret** keys — in-memory transfer only, for the crypto ops and the
     * Keystore-wrap layer. The caller MUST NOT write these to the WebDAV disk or a log, and should
     * zeroize its copy when done (Security constraints).
     */
    fun copySignSecret(): ByteArray = signSecret.copyOf()

    fun copyBoxSecret(): ByteArray = boxSecret.copyOf()

    /** Redacted — an Identity must never print its secret material (Security constraints). */
    override fun toString(): String = "Identity(***)"

    companion object {
        const val SIGN_PUB_BYTES = 32
        const val SIGN_SEC_BYTES = 64
        const val BOX_PUB_BYTES = 32
        const val BOX_SEC_BYTES = 32

        /**
         * The byte layout used to persist a full identity (Keystore-wrapped by [IdentityStore]):
         * `signPub(32) ‖ signSec(64) ‖ boxPub(32) ‖ boxSec(32)` = 160 bytes. Public keys are kept
         * alongside the secrets so a load reconstructs the whole identity without re-deriving.
         */
        const val SERIALIZED_BYTES = SIGN_PUB_BYTES + SIGN_SEC_BYTES + BOX_PUB_BYTES + BOX_SEC_BYTES

        // Field offsets in the serialized layout (signPub ‖ signSec ‖ boxPub ‖ boxSec).
        private const val SIGN_PUB_OFF = 0
        private const val SIGN_SEC_OFF = SIGN_PUB_OFF + SIGN_PUB_BYTES
        private const val BOX_PUB_OFF = SIGN_SEC_OFF + SIGN_SEC_BYTES
        private const val BOX_SEC_OFF = BOX_PUB_OFF + BOX_PUB_BYTES

        /** Serialize to the [SERIALIZED_BYTES]-byte layout for Keystore wrapping. In-memory only. */
        fun serialize(identity: Identity): ByteArray {
            val out = ByteArray(SERIALIZED_BYTES)
            identity.signPublic.copyInto(out, SIGN_PUB_OFF)
            identity.signSecret.copyInto(out, SIGN_SEC_OFF)
            identity.boxPublic.copyInto(out, BOX_PUB_OFF)
            identity.boxSecret.copyInto(out, BOX_SEC_OFF)
            return out
        }

        /** Reconstruct from the [SERIALIZED_BYTES]-byte layout, or `null` if the length is wrong. */
        fun deserialize(bytes: ByteArray): Identity? {
            if (bytes.size != SERIALIZED_BYTES) return null
            return Identity(
                signPublic = bytes.copyOfRange(SIGN_PUB_OFF, SIGN_PUB_OFF + SIGN_PUB_BYTES),
                signSecret = bytes.copyOfRange(SIGN_SEC_OFF, SIGN_SEC_OFF + SIGN_SEC_BYTES),
                boxPublic = bytes.copyOfRange(BOX_PUB_OFF, BOX_PUB_OFF + BOX_PUB_BYTES),
                boxSecret = bytes.copyOfRange(BOX_SEC_OFF, BOX_SEC_OFF + BOX_SEC_BYTES),
            )
        }
    }
}
