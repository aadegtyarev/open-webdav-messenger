package org.openwebdav.messenger.message

import org.openwebdav.messenger.identity.IdentityCrypto
import org.openwebdav.messenger.identity.PublicIdentity
import org.openwebdav.messenger.protocol.MessageId

/**
 * Deserializes §8 plaintext bytes into a typed [Message] and verifies the §8.3 Ed25519 signature
 * (`docs/protocol/webdav-layout.md`). Strictly reject-don't-guess (§8.1): every length prefix is
 * validated against the buffer, no `!!` is used, no index/bounds exception escapes — every failure is
 * a typed [ParseResult.Rejected]. The signature is verified LAST, after the structure is fully valid,
 * over the exact `[0 .. len-64)` signed range against the embedded `sender-id-pubkey`.
 *
 * The box public key of [Message.sender] is NOT carried in §8 (only the Ed25519 signing key is signed
 * into the message). A parsed [PublicIdentity] therefore reuses the verified signPub and pairs it with
 * an all-zero box public placeholder — binding the human/box identity is the directory feature's job
 * (§8.3); this layer only proves the signing key signed these bytes.
 */
class MessageParser(private val identity: IdentityCrypto) {
    fun parse(plaintext: ByteArray): ParseResult {
        if (plaintext.size < MessageFormat.MIN_PLAINTEXT_BYTES) {
            return ParseResult.Rejected(RejectReason.MALFORMED)
        }
        val signatureStart = plaintext.size - MessageFormat.SIGNATURE_BYTES
        val cursor = ByteCursor(plaintext)

        val version = cursor.u8() ?: return reject(RejectReason.MALFORMED)
        if (version.toByte() != MessageFormat.FORMAT_VERSION) return reject(RejectReason.UNKNOWN_VERSION)
        val kind = cursor.u8() ?: return reject(RejectReason.MALFORMED)
        val senderPub = cursor.take(MessageFormat.SENDER_PUBKEY_BYTES) ?: return reject(RejectReason.MALFORMED)
        val fieldCount = cursor.u16() ?: return reject(RejectReason.MALFORMED)

        val fields =
            TlvFields.read(cursor, fieldCount, signatureStart)
                ?: return reject(RejectReason.BAD_FIELDS)

        // senderPub came from take(SENDER_PUBKEY_BYTES) above, so it is exactly the width PublicIdentity
        // requires — no runtime length re-check; the format/identity constants agreeing is a build-time
        // invariant (asserted once in the companion `init`), not a live reject path.
        val sender = PublicIdentity(senderPub, ByteArray(PublicIdentity.BOX_PUB_BYTES))
        val built =
            when (kind.toByte()) {
                MessageFormat.KIND_TEXT -> buildText(fields, sender)
                MessageFormat.KIND_REACTION -> buildReaction(fields, sender)
                else -> return reject(RejectReason.UNKNOWN_KIND)
            }
        val message =
            when (built) {
                is BuildOutcome.Ok -> built.message
                is BuildOutcome.Fail -> return reject(built.reason)
            }

        return verifyAndWrap(plaintext, signatureStart, senderPub, message)
    }

    /** Verify the §8.3 signature over `[0 .. signatureStart)`; a hard reject on libsodium failure. */
    private fun verifyAndWrap(
        plaintext: ByteArray,
        signatureStart: Int,
        senderPub: ByteArray,
        message: Message,
    ): ParseResult {
        val signedPayload = plaintext.copyOfRange(0, signatureStart)
        val signature = plaintext.copyOfRange(signatureStart, plaintext.size)
        if (!identity.verify(signature, signedPayload, senderPub)) {
            return reject(RejectReason.BAD_SIGNATURE)
        }
        return ParseResult.Parsed(message)
    }

    /** §8.4: assemble a [TextMessage], or a typed fail on a missing/unknown/malformed field. */
    private fun buildText(
        fields: TlvFields,
        sender: PublicIdentity,
    ): BuildOutcome {
        if (!fields.onlyHas(TEXT_TAGS)) return BuildOutcome.Fail(RejectReason.BAD_FIELDS)
        val chatId = utf8(fields.get(MessageFormat.TAG_CHAT_ID)) ?: return badFields()
        val body = utf8(fields.get(MessageFormat.TAG_BODY)) ?: return badFields()
        val timestamp = BigEndian.readUint64Be(fields.get(MessageFormat.TAG_SEND_TIMESTAMP)) ?: return badFields()
        val replyToBytes = fields.get(MessageFormat.TAG_REPLY_TO)
        val replyTo =
            if (replyToBytes == null) {
                null
            } else {
                reference(replyToBytes) ?: return badFields() // present but not a well-formed §2 ref = reject
            }
        return BuildOutcome.Ok(TextMessage(chatId, sender, replyTo, body, timestamp))
    }

    /** §8.5: assemble a [ReactionMessage], or a typed fail on a missing/unknown/malformed/out-of-range field. */
    private fun buildReaction(
        fields: TlvFields,
        sender: PublicIdentity,
    ): BuildOutcome {
        if (!fields.onlyHas(REACTION_TAGS)) return BuildOutcome.Fail(RejectReason.BAD_FIELDS)
        val chatId = utf8(fields.get(MessageFormat.TAG_CHAT_ID)) ?: return badFields()
        val targetId = reference(fields.get(MessageFormat.TAG_TARGET_ID)) ?: return badFields()
        val indexBytes = fields.get(MessageFormat.TAG_REACTION_INDEX) ?: return badFields()
        val index = reactionIndex(indexBytes) ?: return outOfRange() // ∉ 0..4 or wrong width (§8.5)
        return BuildOutcome.Ok(ReactionMessage(chatId, sender, targetId, index))
    }

    private fun badFields(): BuildOutcome = BuildOutcome.Fail(RejectReason.BAD_FIELDS)

    private fun outOfRange(): BuildOutcome = BuildOutcome.Fail(RejectReason.OUT_OF_RANGE)

    /** Decode a UTF-8 string field; `null` if the field is absent. */
    private fun utf8(value: ByteArray?): String? = value?.toString(Charsets.UTF_8)

    /**
     * Decode a content-addressed REFERENCE field (`reply-to` / `target-id`) and validate it against the
     * §2 grammar (alphabet + the `~` split + the two component lengths/charsets) — well-formedness ONLY,
     * NOT by reconstructing from this message's own bytes (§8.6, corrected §8). A well-formed reference
     * to a not-yet-received message is valid (resolution is the reader's concern); a malformed value is
     * rejected. `null` if absent or not a well-formed §2 file name.
     */
    private fun reference(value: ByteArray?): String? {
        val s = utf8(value) ?: return null
        return if (MessageId.isWellFormedMessageId(s)) s else null
    }

    /** Decode a 1-byte reaction-index and enforce the closed 0..4 range (§8.5); `null` if wrong width or ∉ 0..4. */
    private fun reactionIndex(value: ByteArray): Int? {
        if (value.size != MessageFormat.REACTION_INDEX_BYTES) return null
        val index = value[0].toInt() and 0xFF
        return if (index in ReactionMessage.MIN_REACTION_INDEX..ReactionMessage.MAX_REACTION_INDEX) index else null
    }

    private fun reject(reason: RejectReason): ParseResult = ParseResult.Rejected(reason)

    /** Internal outcome of kind-specific field assembly: a typed message or a typed reject reason. */
    private sealed interface BuildOutcome {
        data class Ok(val message: Message) : BuildOutcome

        data class Fail(val reason: RejectReason) : BuildOutcome
    }

    companion object {
        init {
            // The §8.2 `sender-id-pubkey` width and PublicIdentity's Ed25519 width are two names for the
            // same 32-byte key; this build-time guard makes a future drift fail loudly here instead of
            // leaving a never-firing runtime length re-check on the parse path (§8.2).
            require(MessageFormat.SENDER_PUBKEY_BYTES == PublicIdentity.SIGN_PUB_BYTES) {
                "SENDER_PUBKEY_BYTES must equal PublicIdentity.SIGN_PUB_BYTES"
            }
        }

        private val TEXT_TAGS =
            setOf(
                MessageFormat.TAG_CHAT_ID,
                MessageFormat.TAG_REPLY_TO,
                MessageFormat.TAG_BODY,
                MessageFormat.TAG_SEND_TIMESTAMP,
            )
        private val REACTION_TAGS =
            setOf(
                MessageFormat.TAG_CHAT_ID,
                MessageFormat.TAG_TARGET_ID,
                MessageFormat.TAG_REACTION_INDEX,
            )
    }
}
