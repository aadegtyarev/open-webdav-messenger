package org.openwebdav.messenger.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * Unit tests for the on-disk protocol primitives against `docs/protocol/webdav-layout.md`
 * §1.2 (inbox-id), §2 (message-id / content-hash), §4 (order-token), §5 (envelope framing).
 */
class ProtocolPrimitivesTest {
    // §2: content-hash = b32lower(SHA-256(file-bytes))[0:32], 32 chars, alphabet [a-z2-7].
    @Test
    fun content_hash_is_32_char_base32lower_of_sha256() {
        val bytes = "hello".toByteArray()
        val hash = MessageId.contentHash(bytes)
        assertEquals(32, hash.length)
        assertTrue(hash.all { it in "abcdefghijklmnopqrstuvwxyz234567" })

        val expected =
            Base32.encodeBase32Lower(MessageDigest.getInstance("SHA-256").digest(bytes)).substring(0, 32)
        assertEquals(expected, hash)
    }

    // §2/§4: message-id = order-token "~" content-hash. The order-token length is the sum of the
    // §4 fixed field widths (ts 11 + "-" + sender-tag 8 + "-" + seq 8 = 29); content-hash is 32;
    // so the message-id is 29 + 1 + 32 = 62. (The §2/§4 "30 chars" / "63" summary annotations are
    // off by one vs the field widths — flagged to pm-architect as a spec-defect; the field widths
    // are the precise, load-bearing definitions implemented here.)
    @Test
    fun message_id_is_order_token_tilde_content_hash() {
        val token = OrderToken.build(unixMillis = 1_717_000_000_000L, senderIdentifier = "alice", seq = 1)
        val id = MessageId.messageId(token, "payload".toByteArray())
        assertEquals(OrderToken.LENGTH + 1 + 32, id.length)
        assertEquals(OrderToken.LENGTH, id.indexOf(MessageId.SEPARATOR))
        val (splitToken, splitHash) = MessageId.splitMessageId(id)!!
        assertEquals(token, splitToken)
        assertEquals(MessageId.contentHash("payload".toByteArray()), splitHash)
    }

    // §2: identical bytes → identical name (idempotent), different bytes → different name.
    @Test
    fun content_addressing_is_idempotent_and_collision_free() {
        val token = OrderToken.build(1_717_000_000_000L, "alice", 1)
        assertEquals(
            MessageId.messageId(token, "x".toByteArray()),
            MessageId.messageId(token, "x".toByteArray()),
        )
        assertNotEquals(
            MessageId.messageId(token, "x".toByteArray()),
            MessageId.messageId(token, "y".toByteArray()),
        )
    }

    // §1.2: inbox-id = b32lower(SHA-256(recipient ‖ 0x1F ‖ chat-id))[0:26]; domain-separated.
    @Test
    fun inbox_id_is_26_chars_and_domain_separated() {
        val id = MessageId.inboxId("bob", "chat-1")
        assertEquals(26, id.length)
        assertTrue(id.all { it in "abcdefghijklmnopqrstuvwxyz234567" })
        // recipient="ab"+chat="c" must not collide with recipient="a"+chat="bc" (0x1F separator).
        assertNotEquals(MessageId.inboxId("ab", "c"), MessageId.inboxId("a", "bc"))
    }

    // §4: order-token is lexicographically sortable — string sort equals (ts, seq) numeric sort.
    @Test
    fun order_token_is_lexicographically_sortable() {
        val earlier = OrderToken.build(1_000L, "alice", 1)
        val later = OrderToken.build(2_000L, "alice", 1)
        assertTrue(earlier < later)

        val seq1 = OrderToken.build(1_000L, "alice", 1)
        val seq2 = OrderToken.build(1_000L, "alice", 2)
        assertTrue(seq1 < seq2)
        assertEquals(OrderToken.LENGTH, earlier.length)
    }

    // §5: writer frames magic "OWDM" + version 0x01 + codec-id 0x00 + flags/reserved 0x00 + blob.
    @Test
    fun envelope_write_then_read_roundtrips_opaque_blob() {
        val blob = byteArrayOf(1, 2, 3, 4, 5)
        val framed = Envelope.write(blob)
        assertEquals(Envelope.HEADER_SIZE + blob.size, framed.size)
        assertEquals('O'.code.toByte(), framed[0])
        assertEquals(0x01.toByte(), framed[4]) // envelope-version
        assertEquals(0x00.toByte(), framed[5]) // codec-id = none
        assertTrue(blob.contentEquals(Envelope.read(framed)))
    }

    // §7: reject-don't-guess — bad magic / unknown version / truncation → null (not-understood).
    @Test
    fun envelope_read_rejects_bad_magic_and_truncation() {
        assertNull(Envelope.read(byteArrayOf(0, 0, 0, 0, 1, 0, 0, 0)))
        assertNull(Envelope.read(byteArrayOf(1, 2, 3))) // shorter than header
        val badVersion = Envelope.write(byteArrayOf(9)).copyOf()
        badVersion[4] = 0x02
        assertNull(Envelope.read(badVersion))
    }

    // §5/§7 (review finding 8): codec-id is validated on read. The defined set is {0x00 none,
    // 0x01 deflate}; an unknown codec-id is rejected like bad magic — the blob is NOT passed up.
    @Test
    fun envelope_read_rejects_unknown_codec_id() {
        val accepted = Envelope.write(byteArrayOf(7, 7, 7))
        // 0x00 (none) and 0x01 (deflate) are accepted; the blob round-trips.
        assertTrue(byteArrayOf(7, 7, 7).contentEquals(Envelope.read(accepted)))
        val deflate = accepted.copyOf()
        deflate[5] = Envelope.CODEC_DEFLATE
        assertTrue(byteArrayOf(7, 7, 7).contentEquals(Envelope.read(deflate)))

        // An unknown codec-id (0x7F) is rejected like a bad-magic frame → null (not-understood).
        val unknownCodec = accepted.copyOf()
        unknownCodec[5] = 0x7F
        assertNull(Envelope.read(unknownCodec))
    }

    // §5/§5.1: readFrame exposes the validated 8-byte header (the AEAD AAD) alongside the blob, so the
    // crypto layer reuses it instead of re-slicing the header independently. read() == readFrame().blob.
    @Test
    fun envelope_read_frame_exposes_validated_header_and_blob() {
        val blob = byteArrayOf(9, 8, 7, 6)
        val framed = Envelope.write(blob)
        val frame = Envelope.readFrame(framed)!!
        // The frame header is exactly the first 8 bytes; the blob is everything after.
        assertTrue(framed.copyOfRange(0, Envelope.HEADER_SIZE).contentEquals(frame.header))
        assertTrue(blob.contentEquals(frame.blob))
        // read() is the blob-only view over the same parse.
        assertTrue(Envelope.read(framed)!!.contentEquals(frame.blob))
        // A not-understood frame returns null from readFrame too.
        assertNull(Envelope.readFrame(byteArrayOf(0, 0, 0, 0, 1, 0, 0, 0)))
    }

    // §2: splitMessageId rejects names that are not well-formed message-ids.
    @Test
    fun split_message_id_rejects_malformed_names() {
        assertNull(MessageId.splitMessageId("no-separator-here"))
        assertNull(MessageId.splitMessageId("~justhash"))
        assertNull(MessageId.splitMessageId("token~"))
        assertNull(MessageId.splitMessageId("a~b~c"))
        assertNull(MessageId.splitMessageId("token~tooshort"))
    }
}
