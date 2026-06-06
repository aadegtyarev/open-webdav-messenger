package org.openwebdav.messenger.crypto

/**
 * A 32-byte symmetric per-chat content key — the input to XChaCha20-Poly1305 AEAD
 * (`docs/protocol/webdav-layout.md` §5.1, `docs/architecture.md` decision 9).
 *
 * The raw bytes are held **in memory only**. Per the Security constraints
 * (`docs/architecture.md` → "Content keys are Keystore-wrapped, never on disk") the raw key
 * is **never written to the WebDAV disk and never logged** — [toString] is redacted, and the
 * raw bytes are reachable only via [copyBytes] / [export] (used by the Keystore wrap layer and
 * the future invite feature, both in-memory). It does not implement [equals]/[hashCode] on the
 * key material to avoid accidental constant-time leaks; key identity is by chat-id, not by bytes.
 *
 * @property bytes the raw 32-byte key. Defensively copied on construction so the caller's array
 *   (which it should zeroize) does not alias this key.
 */
class ChatKey private constructor(private val bytes: ByteArray) {
    init {
        require(bytes.size == KEY_BYTES) { "ChatKey must be $KEY_BYTES bytes, got ${bytes.size}" }
    }

    /** A fresh copy of the raw key bytes — for the AEAD layer and Keystore wrap. In-memory only. */
    fun copyBytes(): ByteArray = bytes.copyOf()

    /**
     * Export the raw key bytes for the future invite/QR feature to carry a random key out-of-band
     * (`docs/architecture.md` decision 9 follow-on (c)). In-memory transfer only — the caller MUST
     * NOT write the result to the WebDAV disk or a log. Identical to [copyBytes]; named to make the
     * intent explicit at the call site.
     */
    fun export(): ByteArray = bytes.copyOf()

    /** Redacted — a ChatKey must never print its material (Security constraints). */
    override fun toString(): String = "ChatKey(***)"

    companion object {
        /**
         * XChaCha20-Poly1305 key length (libsodium `crypto_aead_xchacha20poly1305_ietf_KEYBYTES`).
         * Single-sourced from [Aead.KEY_BYTES] (the libsodium-derived home) so the key width and the
         * AEAD framing cannot drift apart.
         */
        val KEY_BYTES = Aead.KEY_BYTES

        /**
         * Wrap [raw] as a [ChatKey]. The bytes are defensively copied; the caller may zeroize its
         * own array afterwards. Used by the key sources and by raw-key import (invite feature).
         */
        fun fromBytes(raw: ByteArray): ChatKey {
            require(raw.size == KEY_BYTES) { "ChatKey must be $KEY_BYTES bytes, got ${raw.size}" }
            return ChatKey(raw.copyOf())
        }
    }
}
