package org.openwebdav.messenger.crypto

import org.openwebdav.messenger.protocol.Envelope

/**
 * Ties the AEAD substrate to the envelope framing (`docs/protocol/webdav-layout.md` §5 + §5.1):
 * builds the 8-byte header **first**, binds it as AEAD AAD, seals the plaintext, and assembles the
 * final envelope file = `header8 ‖ blob`. The transport content-hashes these final bytes (§2), so
 * the random per-seal nonce keeps identical plaintexts producing distinct ids.
 *
 * This is the seam the message-model / transport features call: in goes plaintext + a [ChatKey],
 * out come the exact bytes to `PUT`; in come the exact bytes `GET`, out comes [OpenResult].
 * Compression stays off here — `codec-id` is `0x00 (none)` for the crypto feature (§5.1).
 */
class MessageCrypto(private val aead: Aead) {
    /**
     * Seal [plaintext] under [chatKey] into a complete envelope file. The codec-id is `none`; the
     * 8-byte header is bound as AAD. Returns `header8 ‖ blob` — the exact bytes to write to disk.
     */
    fun sealEnvelope(
        chatKey: ChatKey,
        plaintext: ByteArray,
    ): ByteArray {
        val header = Envelope.header(Envelope.CODEC_NONE)
        val blob = aead.seal(chatKey, header, plaintext)
        return header + blob
    }

    /**
     * Open a complete envelope [fileBytes] under [chatKey]. The header is parsed and validated by
     * [Envelope.read] (reject-don't-guess: bad magic / unknown version / unknown codec → [OpenResult.Rejected]);
     * the same 8-byte header is then passed as AAD to the AEAD open, so any header tamper fails the tag.
     * Returns [OpenResult.Opened] with the exact plaintext, or [OpenResult.Rejected].
     */
    fun openEnvelope(
        chatKey: ChatKey,
        fileBytes: ByteArray,
    ): OpenResult {
        // Reuse the header that Envelope.readFrame already validated/exposes — do not re-slice the
        // header independently (single derivation; the same 8 bytes go to the AEAD as AAD).
        val frame = Envelope.readFrame(fileBytes) ?: return OpenResult.Rejected
        return aead.open(chatKey, frame.header, frame.blob)
    }
}
