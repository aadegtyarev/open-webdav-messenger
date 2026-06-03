package org.openwebdav.messenger.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §8.1 reject-don't-guess tests (`docs/protocol/webdav-layout.md`): every malformed / forged / unknown
 * input collapses to a typed [ParseResult.Rejected] — never a throw, never a partial parse, never a
 * crash. Real libsodium Ed25519 verify (the -1 hard-reject path).
 */
class MessageRejectionTest {
    private val serializer = MessageTestSupport.serializer()
    private val parser = MessageTestSupport.parser()

    private fun signed(
        message: Message,
        signSecret: ByteArray,
    ): ByteArray = serializer.signAndSerialize(message, signSecret)

    @Test
    fun forged_sender_rejected() {
        // The message claims Alice's key (embedded) but is signed with Bob's secret — impersonation.
        val alice = MessageTestSupport.newIdentity()
        val bob = MessageTestSupport.newIdentity()
        val msg = MessageTestSupport.textMessage(alice) // sender-id-pubkey = Alice's
        val bytes = signed(msg, bob.copySignSecret()) // ...but signed by Bob
        val result = parser.parse(bytes)
        assertEquals(RejectReason.BAD_SIGNATURE, (result as ParseResult.Rejected).reason)
    }

    @Test
    fun impersonation_signature_mismatch_rejected() {
        // Interaction scenario mirror: a member cannot post as another member.
        val realSender = MessageTestSupport.newIdentity()
        val attacker = MessageTestSupport.newIdentity()
        // Attacker builds a message embedding the real sender's public key but can only sign with theirs.
        val msg = MessageTestSupport.reactionMessage(realSender)
        val bytes = signed(msg, attacker.copySignSecret())
        assertTrue(parser.parse(bytes) is ParseResult.Rejected)
    }

    @Test
    fun tampered_payload_rejected() {
        val id = MessageTestSupport.newIdentity()
        val bytes = signed(MessageTestSupport.textMessage(id, body = "original"), id.copySignSecret())
        // Flip a byte inside the signed payload (a body byte, well before the trailing 64-byte signature).
        val tampered = bytes.copyOf()
        val flipAt = MessageFormat.PREFIX_BYTES + 10
        tampered[flipAt] = (tampered[flipAt].toInt() xor 0x01).toByte()
        assertEquals(RejectReason.BAD_SIGNATURE, (parser.parse(tampered) as ParseResult.Rejected).reason)
    }

    @Test
    fun unknown_version_rejected() {
        val id = MessageTestSupport.newIdentity()
        val bytes = signed(MessageTestSupport.textMessage(id), id.copySignSecret())
        bytes[0] = 0x02 // a msg-format-version this build does not implement
        assertEquals(RejectReason.UNKNOWN_VERSION, (parser.parse(bytes) as ParseResult.Rejected).reason)
    }

    @Test
    fun unknown_version_is_typed_rejection() {
        // Interaction mirror: a future-version message is a typed rejection, not a partial parse.
        val id = MessageTestSupport.newIdentity()
        val bytes = signed(MessageTestSupport.reactionMessage(id), id.copySignSecret())
        bytes[0] = 0x7F
        val result = parser.parse(bytes)
        assertTrue(result is ParseResult.Rejected)
        assertEquals(RejectReason.UNKNOWN_VERSION, (result as ParseResult.Rejected).reason)
    }

    @Test
    fun unknown_kind_rejected() {
        val id = MessageTestSupport.newIdentity()
        val bytes = signed(MessageTestSupport.textMessage(id), id.copySignSecret())
        bytes[1] = 0x03 // 0x03 is RESERVED (edit/delete/system) — not defined now
        assertEquals(RejectReason.UNKNOWN_KIND, (parser.parse(bytes) as ParseResult.Rejected).reason)
    }

    @Test
    fun malformed_plaintext_rejected() {
        // Garbage (e.g. a wrong-key AEAD decrypt would yield random bytes) → reject, no index crash.
        assertTrue(parser.parse(ByteArray(0)) is ParseResult.Rejected)
        assertTrue(parser.parse(ByteArray(50) { 0xAB.toByte() }) is ParseResult.Rejected)
        assertTrue(parser.parse(ByteArray(200) { (it * 7).toByte() }) is ParseResult.Rejected)
        // A valid signed message truncated mid-buffer → reject (length prefix overruns), not a crash.
        val id = MessageTestSupport.newIdentity()
        val bytes = serializer.signAndSerialize(MessageTestSupport.textMessage(id), id.copySignSecret())
        assertTrue(parser.parse(bytes.copyOf(bytes.size - 5)) is ParseResult.Rejected)
    }

    @Test
    fun reaction_index_bounds() {
        val id = MessageTestSupport.newIdentity()
        // 0..4 are accepted.
        for (i in 0..4) {
            val ok = serializer.signAndSerialize(MessageTestSupport.reactionMessage(id, index = i), id.copySignSecret())
            assertTrue("index $i should parse", parser.parse(ok) is ParseResult.Parsed)
        }
        // index 5 (∉ 0..4): build a valid index-4 reaction, then overwrite the reaction-index value byte
        // to 5 and re-sign so the signature is valid — isolating the index-range check, not the signature.
        val valid = MessageTestSupport.reactionMessage(id, index = 4)
        val payload = serializer.serializeSignedPayload(valid)
        payload[payload.size - 1] = 5 // the reaction-index value is the last byte of the signed payload
        val resigned = payload + MessageTestSupport.identityCrypto().sign(payload, id.copySignSecret())
        assertEquals(RejectReason.OUT_OF_RANGE, (parser.parse(resigned) as ParseResult.Rejected).reason)
    }

    @Test
    fun reaction_to_unknown_target_parses() {
        // A reaction to a not-yet-seen (but grammar-valid) target id parses fine; applying it is sync/UI.
        val id = MessageTestSupport.newIdentity()
        val msg = MessageTestSupport.reactionMessage(id, targetSeed = "never-seen-target", index = 2)
        val bytes = serializer.signAndSerialize(msg, id.copySignSecret())
        val parsed = (parser.parse(bytes) as ParseResult.Parsed).message as ReactionMessage
        assertEquals(msg.targetId, parsed.targetId)
    }

    @Test
    fun wellformed_reference_to_unknown_message_parses() {
        // §8.6 / §4 causality: a `reply-to` that is a well-formed §2 file name but names a message this
        // reader has NOT received parses successfully — resolution is the reader's (sync/UI) concern, a
        // well-formed reference is never a parse error. (The reference is grammar-valid by construction.)
        val id = MessageTestSupport.newIdentity()
        val unknownRef = MessageTestSupport.messageId("a-message-we-have-never-received")
        val msg = MessageTestSupport.textMessage(id, replyTo = unknownRef)
        val bytes = serializer.signAndSerialize(msg, id.copySignSecret())
        val parsed = (parser.parse(bytes) as ParseResult.Parsed).message as TextMessage
        assertEquals(unknownRef, parsed.replyTo)
    }

    @Test
    fun absurd_field_count_rejected_without_large_allocation() {
        // SECURITY (allocation amplifier): `field-count` is an untrusted uint16 read BEFORE the §8.3
        // signature is checked. A ~100-byte plaintext that lies `field-count = 0xFFFF` (65535) must be a
        // typed reject via the normal parse path — the parser must NOT pre-size a 65535-entry map (~0.5 MB)
        // off the untrusted count. Here the 100-byte buffer has room for 0 TLV triples, so a claim of
        // 65535 cannot fit and is rejected as BAD_FIELDS before any field byte is read.
        val plaintext = ByteArray(MessageFormat.MIN_PLAINTEXT_BYTES) // 36-byte prefix + 0 fields + 64-byte sig
        plaintext[0] = MessageFormat.FORMAT_VERSION
        plaintext[1] = MessageFormat.KIND_TEXT
        // field-count is the big-endian uint16 at the end of the fixed prefix (offset 34..35).
        plaintext[MessageFormat.PREFIX_BYTES - 2] = 0xFF.toByte()
        plaintext[MessageFormat.PREFIX_BYTES - 1] = 0xFF.toByte()
        val result = parser.parse(plaintext)
        assertEquals(RejectReason.BAD_FIELDS, (result as ParseResult.Rejected).reason)
    }

    @Test
    fun lying_field_count_too_low_rejected() {
        // The complementary direction: a count that is too LOW (claims fewer triples than the bytes hold)
        // still rejects — the field region must end EXACTLY at the signature (§8.2). Build a valid text
        // message (3+ fields), then overwrite its field-count to 1 and re-sign so only the count lies.
        val id = MessageTestSupport.newIdentity()
        val payload = serializer.serializeSignedPayload(MessageTestSupport.textMessage(id))
        // field-count uint16 at offset 34..35; force it to 1 (under-count → trailing bytes before sig).
        payload[MessageFormat.PREFIX_BYTES - 2] = 0x00
        payload[MessageFormat.PREFIX_BYTES - 1] = 0x01
        val resigned = payload + MessageTestSupport.identityCrypto().sign(payload, id.copySignSecret())
        assertEquals(RejectReason.BAD_FIELDS, (parser.parse(resigned) as ParseResult.Rejected).reason)
    }

    @Test
    fun malformed_reference_rejected() {
        // §8.4/§8.5: a `reply-to` / `target-id` value that is NOT a well-formed §2 file name (alphabet,
        // the `~` split, the two component lengths/charsets) is rejected on parse — validated as a
        // §2-grammar string, NOT reconstructed from this message's own bytes. The serializer writes the
        // raw string verbatim (no write-side validation), so a re-signed bad value isolates the
        // parse-side reference check.
        val id = MessageTestSupport.newIdentity()

        // A text message whose reply-to is not a §2 file name (no `~`, wrong length/alphabet).
        val badReply = MessageTestSupport.textMessage(id, replyTo = "not-a-valid-message-id")
        val badReplyBytes = serializer.signAndSerialize(badReply, id.copySignSecret())
        assertEquals(RejectReason.BAD_FIELDS, (parser.parse(badReplyBytes) as ParseResult.Rejected).reason)

        // A reaction whose target-id has a valid shape but an out-of-§2-alphabet character in the hash.
        // order-token (29) + "~" + 32 chars, but the hash uses '0'/'1'/'8'/'9' ∉ Base32 [a-z2-7].
        val badTarget =
            ReactionMessage(
                chatId = "chat-abc",
                sender = id.publicIdentity(),
                targetId = "00000000000-aaaaaaaa-00000000~00000000000000000000000000000000",
                reactionIndex = 1,
            )
        val badTargetBytes = serializer.signAndSerialize(badTarget, id.copySignSecret())
        assertEquals(RejectReason.BAD_FIELDS, (parser.parse(badTargetBytes) as ParseResult.Rejected).reason)
    }
}
