package org.openwebdav.messenger.identity

/**
 * The result of [IdentityCrypto.openSealed]: either the recovered plaintext or a typed rejection —
 * never an exception, never silently-wrong bytes (mirrors the AEAD `OpenResult` discipline in
 * `crypto/`). A sealed blob that does not open under the recipient's keypair (wrong recipient,
 * tampered, truncated) yields [Rejected].
 */
sealed interface SealedResult {
    /** The sealed box opened; [bytes] is the exact original plaintext. */
    data class Opened(val bytes: ByteArray) : SealedResult {
        override fun equals(other: Any?): Boolean = other is Opened && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    /**
     * The sealed box did not open under this keypair (wrong recipient / tampered / too short). The
     * payload is dropped — not surfaced as plaintext, not crashed.
     */
    data object Rejected : SealedResult
}
