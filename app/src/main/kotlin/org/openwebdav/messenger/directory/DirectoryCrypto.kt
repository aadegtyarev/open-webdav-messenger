package org.openwebdav.messenger.directory

import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.crypto.MessageCrypto
import org.openwebdav.messenger.crypto.OpenResult
import org.openwebdav.messenger.identity.IdentityCrypto

/**
 * Composes the §10.3 inner directory payload with the §5/§5.1 crypto substrate
 * (`docs/protocol/webdav-layout.md`): the build path is `signAndSerialize → MessageCrypto.sealEnvelope`,
 * and the open path is `MessageCrypto.openEnvelope → DirectoryEntryCodec.parse`. The signed+serialized
 * §10.3 inner payload is EXACTLY the bytes the AEAD seals/opens (§10.2), sealed under the **community
 * key** (a [ChatKey] from the same family as `known`/`random`, decision 9; `codec-id = 0x00`).
 *
 * This is the seam [DirectoryService] calls to turn an entry into envelope bytes to `PUT`, and a `GET`
 * body back into a verified [DirectoryEntry]. It reuses `crypto/MessageCrypto` (the §5 envelope + AEAD,
 * header bound as AAD) and `identity/IdentityCrypto` (Ed25519 sign/verify) **verbatim** — no new crypto,
 * no hand-rolled primitive (SC9).
 */
internal class DirectoryCrypto(
    private val messageCrypto: MessageCrypto,
    private val codec: DirectoryEntryCodec,
) {
    /**
     * Serialize + §10.3-sign an entry (display-name + the two public keys + version-counter) with
     * [signingSecret], then AEAD-seal the inner payload under [communityKey] into a complete §10.2
     * envelope file (`header8 ‖ blob`). The returned bytes are exactly what the transport `PUT`s; the
     * §10.4 content-addressed name is computed over them by [DirectoryService].
     */
    fun sealEntry(
        displayName: String,
        signingPublic: ByteArray,
        boxPublic: ByteArray,
        versionCounter: Long,
        signingSecret: ByteArray,
        communityKey: ChatKey,
    ): ByteArray {
        val innerPayload = codec.signAndSerialize(displayName, signingPublic, boxPublic, versionCounter, signingSecret)
        return messageCrypto.sealEnvelope(communityKey, innerPayload)
    }

    /**
     * AEAD-open [envelopeBytes] under [communityKey], then §10.3-parse + Ed25519-verify the inner
     * payload. Every failure is a typed [DirectoryParseResult.Rejected] — never a crash:
     *  - the §5/§5.1 AEAD reject (wrong/absent community key, tampered header/ciphertext, truncated
     *    blob) maps to [DirectoryRejectReason.MALFORMED] (the entry is dropped, §10.6);
     *  - an inner parse/verify failure is the codec's typed reason.
     */
    fun openEntry(
        envelopeBytes: ByteArray,
        communityKey: ChatKey,
    ): DirectoryParseResult =
        when (val opened = messageCrypto.openEnvelope(communityKey, envelopeBytes)) {
            is OpenResult.Opened -> codec.parse(opened.bytes)
            OpenResult.Rejected -> DirectoryParseResult.Rejected(DirectoryRejectReason.MALFORMED)
        }

    companion object {
        /** Wire a [DirectoryCrypto] from the shared substrates (the seam [DirectoryService] constructs). */
        fun create(
            messageCrypto: MessageCrypto,
            identity: IdentityCrypto,
        ): DirectoryCrypto = DirectoryCrypto(messageCrypto, DirectoryEntryCodec(identity))
    }
}
