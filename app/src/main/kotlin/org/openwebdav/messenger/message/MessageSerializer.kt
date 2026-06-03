package org.openwebdav.messenger.message

import org.openwebdav.messenger.identity.IdentityCrypto
import java.io.ByteArrayOutputStream

/**
 * Serializes a typed [Message] to the §8 plaintext bytes and applies the §8.3 per-message Ed25519
 * signature (`docs/protocol/webdav-layout.md`). The output is exactly the plaintext fed to
 * `Aead.seal` (§8 relationship-to-outer-frame); compression stays off (`codec-id = 0x00`).
 *
 * Layout produced (§8.2):
 * ```
 * msg-format-version(0x01) ‖ kind ‖ sender-id-pubkey(32) ‖ field-count(uint16 BE) ‖ TLV-fields ‖ signature(64)
 * ```
 * The signed payload (§8.3) is the contiguous range `[0 .. len-64)` — version through the last TLV
 * field; the 64-byte signature is appended last and is NOT part of the signed range.
 */
internal class MessageSerializer(private val identity: IdentityCrypto) {
    /**
     * Build the signed payload for [message] (§8.2 prefix + ordered TLV fields), Ed25519-sign that exact
     * byte range with [senderSignSecret] (§8.3), and append the 64-byte signature. Returns the complete
     * §8 plaintext ready for `Aead.seal`.
     *
     * The sender public key serialized in the prefix is taken from `message.sender.signPub`; the caller
     * is responsible for [senderSignSecret] matching it (a mismatch produces a message that fails verify
     * on parse — exactly the impersonation-rejection path, §8.3).
     */
    fun signAndSerialize(
        message: Message,
        senderSignSecret: ByteArray,
    ): ByteArray {
        val signedPayload = serializeSignedPayload(message)
        val signature = identity.sign(signedPayload, senderSignSecret)
        check(signature.size == MessageFormat.SIGNATURE_BYTES) {
            "Ed25519 detached signature must be ${MessageFormat.SIGNATURE_BYTES} bytes"
        }
        return signedPayload + signature
    }

    /** §8.3: the signed-payload byte range — `version ‖ kind ‖ sender-id-pubkey ‖ field-count ‖ fields`. */
    fun serializeSignedPayload(message: Message): ByteArray {
        // Assemble the TLV fields ONCE; the written `field-count` is derived from this list's size, so
        // the count and the emitted bytes share a single source of truth and cannot drift (§8.2).
        val fields = tlvFields(message)
        val out = ByteArrayOutputStream(MessageFormat.PREFIX_BYTES + fields.sumOf { it.encodedSize })
        out.write(MessageFormat.FORMAT_VERSION.toInt())
        out.write(kindByte(message).toInt())
        out.write(message.sender.signPub) // 32-byte sender-id-pubkey (validated 32 by PublicIdentity)
        BigEndian.writeUint16Be(out, fields.size)
        for (tlv in fields) tlv.writeTo(out)
        return out.toByteArray()
    }

    private fun kindByte(message: Message): Byte =
        when (message) {
            is TextMessage -> MessageFormat.KIND_TEXT
            is ReactionMessage -> MessageFormat.KIND_REACTION
        }

    /**
     * The ordered TLV field list for [message] (tags ascending per §8.4/§8.5). Optional fields are
     * simply absent from the list (§8.2.1), so its `size` IS the §8.2 `field-count` — one source of
     * truth for both the count written and the bytes emitted.
     */
    private fun tlvFields(message: Message): List<Tlv> =
        when (message) {
            is TextMessage ->
                buildList {
                    add(stringTlv(MessageFormat.TAG_CHAT_ID, message.chatId))
                    message.replyTo?.let { add(stringTlv(MessageFormat.TAG_REPLY_TO, it)) }
                    add(stringTlv(MessageFormat.TAG_BODY, message.body))
                    add(timestampTlv(message.sendTimestampMillis))
                }
            is ReactionMessage ->
                listOf(
                    stringTlv(MessageFormat.TAG_CHAT_ID, message.chatId),
                    stringTlv(MessageFormat.TAG_TARGET_ID, message.targetId),
                    reactionIndexTlv(message.reactionIndex),
                )
        }

    private fun stringTlv(
        tag: Byte,
        value: String,
    ): Tlv {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MessageFormat.MAX_TLV_VALUE_LEN) { "TLV value exceeds uint16 length" }
        return Tlv(tag, bytes)
    }

    private fun timestampTlv(millis: Long): Tlv {
        val out = ByteArrayOutputStream(MessageFormat.SEND_TIMESTAMP_BYTES)
        BigEndian.writeUint64Be(out, millis)
        return Tlv(MessageFormat.TAG_SEND_TIMESTAMP, out.toByteArray())
    }

    private fun reactionIndexTlv(index: Int): Tlv = Tlv(MessageFormat.TAG_REACTION_INDEX, byteArrayOf((index and 0xFF).toByte()))

    /** One §8.2.1 TLV triple held in memory so the field list can be assembled once and counted. */
    private class Tlv(private val tag: Byte, private val value: ByteArray) {
        /** Wire size of this triple: `tag(1) ‖ length(2) ‖ value`. */
        val encodedSize: Int get() = MessageFormat.TLV_HEADER_BYTES + value.size

        fun writeTo(out: ByteArrayOutputStream) {
            out.write(tag.toInt())
            BigEndian.writeUint16Be(out, value.size)
            out.write(value)
        }
    }
}
