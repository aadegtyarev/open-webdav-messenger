package org.openwebdav.messenger.message

import org.openwebdav.messenger.codec.Codec
import org.openwebdav.messenger.codec.CompressionCodec
import org.openwebdav.messenger.codec.DecompressResult
import org.openwebdav.messenger.codec.DeflateCodec
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.crypto.MessageCrypto
import org.openwebdav.messenger.crypto.OpenResult
import org.openwebdav.messenger.identity.IdentityCrypto
import org.openwebdav.messenger.protocol.MessageId

/**
 * Composes the §8 message model with the §5/§5.1 crypto substrate and the codec layer
 * (`docs/protocol/webdav-layout.md`): the build path is `serialize → signAndSerialize →
 * compress(DEFLATE) → MessageCrypto.sealEnvelope(codec-id=0x01)`, and the open path is
 * `MessageCrypto.openEnvelope → decompress → MessageParser.parse`.
 *
 * The codec sits BETWEEN serialization and AEAD seal (compress-then-encrypt, decision #4). On open
 * the codec-id is read from the envelope header (§5 byte 5) and the matching decompression runs
 * before signature verify and parse. For MVP the sender always uses DEFLATE (hardcoded).
 *
 * This is the seam the `sync` feature will call to turn a typed [Message] into envelope bytes to `PUT`,
 * and a `GET` body back into a typed [Message]. It does NOT write to disk; it exposes [contentName] —
 * the §2 content-addressed file name computed over the sealed bytes. That name IS the message's id
 * (§8.6, corrected §8): it is assigned at seal, never duplicated inside the plaintext. The `sync`
 * feature uses [contentName] as the PUT path / dedup / ordering key.
 *
 * **No inner self-id (§8.6, corrected §8).** A message carries no field naming itself — there is no
 * inner id to cross-check against the §2 name (an inner id forced to equal the §2 content-hash would be
 * an unsatisfiable fixed point over `SHA-256` of the bytes that encrypt it). Identity is the §2 name
 * alone; `reply-to` / `target-id` carry OTHER messages' §2 file names. On-read content integrity is the
 * §3 content-hash check plus the outer Poly1305 tag (§5.1) and the §8.3 Ed25519 signature.
 */
class MessageEnvelope internal constructor(
    private val messageCrypto: MessageCrypto,
    private val serializer: MessageSerializer,
    private val parser: MessageParser,
    private val codec: CompressionCodec,
) {
    /**
     * Serialize + §8.3-sign [message] with [senderSignSecret], compress with DEFLATE, then AEAD-seal
     * under [chatKey] into a complete envelope file (`header8 ‖ blob`) with `codec-id = 0x01 (deflate)`.
     * The returned bytes are exactly what the transport `PUT`s.
     */
    fun seal(
        message: Message,
        chatKey: ChatKey,
        senderSignSecret: ByteArray,
    ): ByteArray {
        val plaintext = serializer.signAndSerialize(message, senderSignSecret)
        val compressed = codec.compress(plaintext)
        return messageCrypto.sealEnvelope(chatKey, compressed, Codec.DEFLATE.id)
    }

    /**
     * AEAD-open [envelopeBytes] under [chatKey], then decompress (according to the on-disk codec-id),
     * then §8-parse + §8.3-verify the plaintext. Returns [ParseResult.Rejected] with [RejectReason.MALFORMED]
     * when the AEAD itself rejects (wrong key, tampered header/ciphertext, truncated blob) or when
     * decompression fails (corrupted/malformed compressed data, oversize output — SC7) — the message
     * layer surfaces a single typed failure, never a crash (plan interaction `crypto_roundtrip_cross_key`).
     */
    fun open(
        envelopeBytes: ByteArray,
        chatKey: ChatKey,
    ): ParseResult =
        when (val opened = messageCrypto.openEnvelope(chatKey, envelopeBytes)) {
            is OpenResult.Opened -> {
                val plaintext =
                    when (val d = decompressSafe(opened.codecId, opened.bytes)) {
                        is DecompressResult.Ok -> d.bytes
                        DecompressResult.Rejected -> return ParseResult.Rejected(RejectReason.MALFORMED)
                    }
                parser.parse(plaintext)
            }
            OpenResult.Rejected -> ParseResult.Rejected(RejectReason.MALFORMED)
        }

    /**
     * §2/§8.6: the message's id — the §2 `content-hash` (`b32lower(SHA-256(file-bytes))[0:32]`) of the
     * sealed [envelopeBytes]. This is the only id space (§8.6); the `sync` feature pairs it with the
     * §4 order-token to form the full §2 file name (`order-token "~" content-hash`) it `PUT`s.
     */
    fun contentName(envelopeBytes: ByteArray): String = MessageId.contentHash(envelopeBytes)

    /**
     * Map a codec-id byte to the matching decompress call (or pass-through for NONE). An unknown codec-id
     * is a typed rejection — reject-don't-guess (§7). A decompression failure (corrupted data / oversize)
     * is likewise a typed rejection (SC7 bounded decompression).
     */
    private fun decompressSafe(
        codecId: Byte,
        bytes: ByteArray,
    ): DecompressResult {
        val c =
            try {
                Codec.fromId(codecId)
            } catch (e: IllegalArgumentException) {
                return DecompressResult.Rejected // unknown codec → reject
            }
        return when (c) {
            Codec.NONE -> DecompressResult.Ok(bytes)
            Codec.DEFLATE -> codec.decompress(bytes)
        }
    }

    companion object {
        /** Wire a [MessageEnvelope] from the shared substrates with DEFLATE compression (the seam the
         * `sync` feature constructs). For MVP the codec is always [DeflateCodec] (decision #4). */
        fun create(
            messageCrypto: MessageCrypto,
            identity: IdentityCrypto,
        ): MessageEnvelope =
            MessageEnvelope(
                messageCrypto = messageCrypto,
                serializer = MessageSerializer(identity),
                parser = MessageParser(identity),
                codec = DeflateCodec(),
            )
    }
}
