package org.openwebdav.messenger.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §8 round-trip + field-fidelity tests (`docs/protocol/webdav-layout.md`): build → signAndSerialize →
 * seal → open → parse+verify yields back the identical typed message. Real libsodium AEAD + Ed25519.
 */
class MessageRoundTripTest {
    private val serializer = MessageTestSupport.serializer()
    private val parser = MessageTestSupport.parser()
    private val envelope = MessageTestSupport.envelope()

    /** Serialize+sign a message, then parse the plaintext directly (no AEAD layer). */
    private fun parsePlaintext(
        message: Message,
        signSecret: ByteArray,
    ): ParseResult = parser.parse(serializer.signAndSerialize(message, signSecret))

    @Test
    fun text_message_roundtrip() {
        val id = MessageTestSupport.newIdentity()
        val msg =
            MessageTestSupport.textMessage(
                id,
                body = "hi there",
                replyTo = MessageTestSupport.messageId("quoted"),
                chatId = "chat-xyz",
            )
        val result = parsePlaintext(msg, id.copySignSecret())
        val parsed = (result as ParseResult.Parsed).message as TextMessage
        assertEquals(msg.chatId, parsed.chatId)
        assertEquals(msg.replyTo, parsed.replyTo)
        assertEquals(msg.body, parsed.body)
        assertEquals(msg.sendTimestampMillis, parsed.sendTimestampMillis)
        assertTrue(msg.sender.signPub.contentEquals(parsed.sender.signPub))
    }

    @Test
    fun reaction_message_roundtrip() {
        val id = MessageTestSupport.newIdentity()
        val msg = MessageTestSupport.reactionMessage(id, index = 4)
        val parsed = (parsePlaintext(msg, id.copySignSecret()) as ParseResult.Parsed).message as ReactionMessage
        assertEquals(msg.chatId, parsed.chatId)
        assertEquals(msg.targetId, parsed.targetId)
        assertEquals(4, parsed.reactionIndex)
    }

    @Test
    fun reply_to_optional() {
        val id = MessageTestSupport.newIdentity()
        val noReply = MessageTestSupport.textMessage(id, replyTo = null)
        val withReply = MessageTestSupport.textMessage(id, replyTo = MessageTestSupport.messageId("the-target"))

        val parsedNo = (parsePlaintext(noReply, id.copySignSecret()) as ParseResult.Parsed).message as TextMessage
        assertNull(parsedNo.replyTo)

        val parsedYes = (parsePlaintext(withReply, id.copySignSecret()) as ParseResult.Parsed).message as TextMessage
        assertEquals(withReply.replyTo, parsedYes.replyTo)
    }

    @Test
    fun markdown_body_preserved() {
        val id = MessageTestSupport.newIdentity()
        // The supported Markdown subset characters, carried RAW (no rendering/normalization, §8.2.1).
        val body = "**bold** _italic_ `code` ~~strike~~ [link](https://x.test) > quote\n- list\n\túñïçödé"
        val msg = MessageTestSupport.textMessage(id, body = body)
        val parsed = (parsePlaintext(msg, id.copySignSecret()) as ParseResult.Parsed).message as TextMessage
        assertEquals(body, parsed.body)
        // Byte-identical UTF-8 (no normalization mangling multibyte chars).
        assertTrue(body.toByteArray(Charsets.UTF_8).contentEquals(parsed.body.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun signature_verifies_against_embedded_sender_key() {
        val id = MessageTestSupport.newIdentity()
        val msg = MessageTestSupport.textMessage(id)
        val result = parsePlaintext(msg, id.copySignSecret())
        // A validly-signed message parses to a verified message whose embedded key is the signer's.
        val parsed = (result as ParseResult.Parsed).message
        assertTrue(id.copySignPublic().contentEquals(parsed.sender.signPub))
    }

    @Test
    fun field_count_equals_emitted_tlv_count() {
        // §8.2 single-source-of-truth: the written `field-count` (prefix uint16 at offset 34..35) MUST
        // equal the number of TLV triples actually emitted. A drift would serialize a count disagreeing
        // with the bytes → every such message is produced then rejected on parse. We count the emitted
        // triples by walking the field region (prefix .. signatureStart) and compare to the header count.
        val id = MessageTestSupport.newIdentity()
        // text without reply-to = 3 fields (chat-id, body, timestamp); text with reply-to = 4;
        // reaction = 3 (chat-id, target-id, reaction-index). Each header count must match the bytes.
        val messages =
            listOf(
                MessageTestSupport.textMessage(id, replyTo = null),
                MessageTestSupport.textMessage(id, replyTo = MessageTestSupport.messageId("q")),
                MessageTestSupport.reactionMessage(id),
            )
        for (msg in messages) {
            // serializeSignedPayload has NO trailing signature, so the field region runs prefix..end.
            val payload = serializer.serializeSignedPayload(msg)
            val headerCount = ((payload[34].toInt() and 0xFF) shl 8) or (payload[35].toInt() and 0xFF)
            assertEquals(countTlvTriples(payload, payload.size), headerCount)
            // And the message round-trips: count agreeing with bytes is exactly what makes parse succeed.
            assertTrue(parsePlaintext(msg, id.copySignSecret()) is ParseResult.Parsed)
        }
    }

    /** Walk the §8.2 field region (prefix..[fieldRegionEnd]) and count the TLV triples it actually holds. */
    private fun countTlvTriples(
        payload: ByteArray,
        fieldRegionEnd: Int,
    ): Int {
        var pos = MessageFormat.PREFIX_BYTES
        var count = 0
        while (pos < fieldRegionEnd) {
            val len = ((payload[pos + 1].toInt() and 0xFF) shl 8) or (payload[pos + 2].toInt() and 0xFF)
            pos += MessageFormat.TLV_HEADER_BYTES + len
            count++
        }
        return count
    }

    @Test
    fun crypto_roundtrip_cross_key() {
        val id = MessageTestSupport.newIdentity()
        val key = MessageTestSupport.fixedChatKey(seed = 11)
        val msg = MessageTestSupport.textMessage(id, body = "sealed body")
        val bytes = envelope.seal(msg, key, id.copySignSecret())

        // Same key → fields back.
        val ok = envelope.open(bytes, key) as ParseResult.Parsed
        assertEquals("sealed body", (ok.message as TextMessage).body)

        // Different key → the AEAD rejects before parse; the message layer surfaces a typed rejection.
        val wrongKey = MessageTestSupport.fixedChatKey(seed = 99)
        val rejected = envelope.open(bytes, wrongKey)
        assertTrue(rejected is ParseResult.Rejected)
    }
}
