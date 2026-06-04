package org.openwebdav.messenger.chatdirectory

import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.crypto.MessageCrypto
import org.openwebdav.messenger.crypto.OpenResult
import org.openwebdav.messenger.identity.IdentityCrypto

/**
 * Composes the §11.3 inner chat-descriptor payload with the §5/§5.1 crypto substrate
 * (`docs/protocol/webdav-layout.md`): the build path is `signAndSerialize → MessageCrypto.sealEnvelope`,
 * and the open path is `MessageCrypto.openEnvelope → ChatDescriptorCodec.parse`. The signed+serialized
 * §11.3 inner payload is EXACTLY the bytes the AEAD seals/opens (§11.2), sealed under the **community
 * key** (a [ChatKey] from the same family as `known`/`random` and the §10 directory, decision 9;
 * `codec-id = 0x00`).
 *
 * This is the seam `ChatDirectoryService` calls to turn a descriptor into envelope bytes to `PUT`, and
 * a `GET` body back into a verified descriptor. It reuses `crypto/MessageCrypto` (the §5 envelope +
 * AEAD, header bound as AAD) and `identity/IdentityCrypto` (Ed25519 sign/verify) **verbatim** — no new
 * crypto, no hand-rolled primitive (SC9). Mirrors the §10.2 `DirectoryCrypto`.
 */
internal class ChatDirectoryCrypto(
    private val messageCrypto: MessageCrypto,
    private val codec: ChatDescriptorCodec,
) {
    /**
     * Serialize + §11.3-sign a group chat descriptor with [signingSecret], then AEAD-seal the inner
     * payload under [communityKey] into a complete §11.2 envelope file (`header8 ‖ blob`). The returned
     * bytes are exactly what the transport `PUT`s; the §11.4 content-addressed name is computed over
     * them by `ChatDirectoryService`. Reached only for a valid group descriptor (a `dm` kind is
     * rejected before this, at the publish call site).
     */
    fun sealDescriptor(
        chatId: ByteArray,
        kind: ChatKind,
        access: ChatAccess,
        title: String,
        signingPublic: ByteArray,
        versionCounter: Long,
        signingSecret: ByteArray,
        communityKey: ChatKey,
    ): ByteArray {
        val innerPayload =
            codec.signAndSerialize(chatId, kind, access, title, signingPublic, versionCounter, signingSecret)
        return messageCrypto.sealEnvelope(communityKey, innerPayload)
    }

    /**
     * AEAD-open [envelopeBytes] under [communityKey], then §11.3-parse + Ed25519-verify the inner
     * payload. Every failure is a typed [ChatParseResult.Rejected] — never a crash:
     *  - the §5/§5.1 AEAD reject (wrong/absent community key, tampered header/ciphertext, truncated
     *    blob) maps to [ChatRejectReason.MALFORMED] (the entry is dropped, §11.6);
     *  - an inner parse/verify/`dm`-drop/invalid-access failure is the codec's typed reason.
     */
    fun openDescriptor(
        envelopeBytes: ByteArray,
        communityKey: ChatKey,
    ): ChatParseResult =
        when (val opened = messageCrypto.openEnvelope(communityKey, envelopeBytes)) {
            is OpenResult.Opened -> codec.parse(opened.bytes)
            OpenResult.Rejected -> ChatParseResult.Rejected(ChatRejectReason.MALFORMED)
        }

    companion object {
        /** Wire a [ChatDirectoryCrypto] from the shared substrates (the seam `ChatDirectoryService` constructs). */
        fun create(
            messageCrypto: MessageCrypto,
            identity: IdentityCrypto,
        ): ChatDirectoryCrypto = ChatDirectoryCrypto(messageCrypto, ChatDescriptorCodec(identity))
    }
}
