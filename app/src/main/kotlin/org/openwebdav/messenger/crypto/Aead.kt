package org.openwebdav.messenger.crypto

import com.goterl.lazysodium.interfaces.AEAD
import org.openwebdav.messenger.protocol.Envelope

/**
 * The result of [Aead.open]: either the recovered plaintext or a typed rejection — never an
 * exception, never silently-wrong bytes (`docs/protocol/webdav-layout.md` §5.1 open/reject discipline).
 */
sealed interface OpenResult {
    /** Authentication succeeded; [bytes] is the exact original plaintext. */
    data class Opened(val bytes: ByteArray) : OpenResult {
        override fun equals(other: Any?): Boolean = other is Opened && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    /**
     * Authentication failed (wrong key, tampered header-AAD, tampered ciphertext/tag) or the blob
     * was too short to be a valid sealed message. The message is dropped — not surfaced, not crashed.
     */
    data object Rejected : OpenResult
}

/**
 * XChaCha20-Poly1305 AEAD seal/open over the ciphertext-blob (`docs/protocol/webdav-layout.md` §5.1).
 *
 * ```
 * blob = nonce(24) ‖ crypto_aead_xchacha20poly1305_ietf_encrypt(plaintext, AAD=header8, nonce, key)
 * ```
 *
 * The 8-byte envelope header is bound as AEAD associated data (AAD) so tampering with
 * magic/version/codec-id/flags/reserved breaks the Poly1305 tag → [OpenResult.Rejected].
 * One AEAD layer regardless of which of the three key sources produced [chatKey] (decision 9).
 */
class Aead(private val native: NativeCrypto) {
    /**
     * Seal [plaintext] under [chatKey] with [header8] bound as AAD. A fresh 24-byte CSPRNG nonce is
     * placed at the start of the returned blob, so two seals of identical plaintext differ — upholding
     * the transport's content-addressing invariant (§2). The blob is the envelope's ciphertext slot.
     */
    fun seal(
        chatKey: ChatKey,
        header8: ByteArray,
        plaintext: ByteArray,
    ): ByteArray {
        require(header8.size == HEADER_SIZE) { "header must be $HEADER_SIZE bytes (the envelope header)" }
        val nonce = native.randomBytes(NONCE_BYTES)
        val key = chatKey.copyBytes()
        try {
            val ciphertextWithTag = native.aeadEncrypt(plaintext, header8, nonce, key)
            val blob = ByteArray(NONCE_BYTES + ciphertextWithTag.size)
            nonce.copyInto(blob, 0)
            ciphertextWithTag.copyInto(blob, NONCE_BYTES)
            return blob
        } finally {
            key.fill(0)
        }
    }

    /**
     * Open [blob] under [chatKey] with [header8] as AAD. Returns [OpenResult.Opened] with the exact
     * original bytes on success, or [OpenResult.Rejected] on any auth failure or a blob shorter than
     * `nonce(24) + tag(16) = 40` bytes (§5.1 truncated-blob guard — a reject, not a bounds error).
     */
    fun open(
        chatKey: ChatKey,
        header8: ByteArray,
        blob: ByteArray,
    ): OpenResult {
        if (header8.size != HEADER_SIZE) return OpenResult.Rejected
        if (blob.size < MIN_BLOB_SIZE) return OpenResult.Rejected
        val nonce = blob.copyOfRange(0, NONCE_BYTES)
        val ciphertextWithTag = blob.copyOfRange(NONCE_BYTES, blob.size)
        val key = chatKey.copyBytes()
        try {
            val plaintext = native.aeadDecrypt(ciphertextWithTag, header8, nonce, key) ?: return OpenResult.Rejected
            return OpenResult.Opened(plaintext)
        } finally {
            key.fill(0)
        }
    }

    companion object {
        /**
         * §5.1: 24-byte (192-bit) XChaCha20 nonce — safe to pick at random. Single-sourced from
         * libsodium's `crypto_aead_xchacha20poly1305_ietf_NPUBBYTES` (the value `LazySodiumCrypto`
         * already validates the nonce width against), so the framing size and the native call agree
         * by construction and cannot drift.
         */
        const val NONCE_BYTES = AEAD.XCHACHA20POLY1305_IETF_NPUBBYTES

        /**
         * §5.1: 16-byte Poly1305 tag appended by libsodium's combined mode. Single-sourced from
         * libsodium's `crypto_aead_xchacha20poly1305_ietf_ABYTES`.
         */
        const val TAG_BYTES = AEAD.XCHACHA20POLY1305_IETF_ABYTES

        /**
         * §5.1: 32-byte XChaCha20-Poly1305 key. Single-sourced from libsodium's
         * `crypto_aead_xchacha20poly1305_ietf_KEYBYTES`; [ChatKey.KEY_BYTES] derives from this home.
         */
        const val KEY_BYTES = AEAD.XCHACHA20POLY1305_IETF_KEYBYTES

        /**
         * §5: the 8-byte envelope header bound as AAD. Single source of truth — references the
         * envelope's own constant so a future framing change is a one-place edit ([Envelope.HEADER_SIZE]).
         */
        val HEADER_SIZE = Envelope.HEADER_SIZE

        /** §5.1: a blob shorter than nonce + tag cannot be a valid sealed message. */
        const val MIN_BLOB_SIZE = NONCE_BYTES + TAG_BYTES
    }
}
