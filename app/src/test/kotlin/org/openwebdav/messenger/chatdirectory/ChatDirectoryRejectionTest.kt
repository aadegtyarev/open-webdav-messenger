package org.openwebdav.messenger.chatdirectory

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §11.6 chat-directory rejection tests (`docs/features/chat-directory_plan.md` Test plan): every failure
 * mode is a typed rejection — the entry is dropped (or refused at publish), never surfaced, never a
 * crash — and the remaining valid entries still read. Covers the DM privacy gate (publish + read), the
 * invalid kind/access reject, wrong/absent community key, tampered ciphertext/payload, wrong signature,
 * and malformed/truncated/wrong-magic blobs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ChatDirectoryRejectionTest {
    private lateinit var server: MockWebServer
    private lateinit var disk: ChatDirectoryFakeDisk

    @Before
    fun setUp() {
        server = MockWebServer()
        disk = ChatDirectoryFakeDisk()
        server.dispatcher = disk
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** dm_kind_rejected_on_publish: a dm kind is refused at publish and NOTHING is written to disk. */
    @Test
    fun dm_kind_rejected_on_publish() =
        runTest {
            val key = ChatDirectoryTestSupport.communityKey()
            val identity = ChatDirectoryTestSupport.newIdentity()
            val service = ChatDirectoryTestSupport.service(server)

            val outcome =
                service.publishChatEntry(identity, "dm-chat".toByteArray(), ChatKind.DM, ChatAccess.PRIVATE, "Secret DM", 1, key)
            assertTrue("dm publish must be a typed rejection", outcome is ChatPublishOutcome.RejectedDm)
            // Nothing was written: the chat-directory collection has no files.
            assertTrue("a rejected dm writes nothing", disk.fileNames(ChatDirectoryPaths.CHAT_DIRECTORY).isEmpty())
            // The read surfaces nothing either.
            assertTrue(service.readChatDirectory(key).entries.isEmpty())
        }

    /**
     * dm_kind_entry_dropped_on_read: a FORGED dm-kind entry (validly sealed + signed) is dropped on read
     * and never surfaced — privacy enforced at read, not only at publish (§11.5).
     */
    @Test
    fun dm_kind_entry_dropped_on_read() =
        runTest {
            val key = ChatDirectoryTestSupport.communityKey()
            val identity = ChatDirectoryTestSupport.newIdentity()
            // Hand-seal a dm-kind entry (bypassing the publish gate) and plant it on disk.
            val forged =
                ChatDirectoryTestSupport.sealRawDescriptor(
                    identity = identity,
                    chatId = "dm-chat".toByteArray(),
                    kindByte = ChatDescriptorFormat.KIND_DM,
                    accessByte = ChatDescriptorFormat.ACCESS_PRIVATE,
                    title = "Forged DM",
                    versionCounter = 1,
                    communityKey = key,
                )
            disk.putFile(ChatDirectoryPaths.entryPath(forged.name), forged.bytes)

            val read = ChatDirectoryTestSupport.service(server).readChatDirectory(key)
            assertTrue("a forged dm entry is never surfaced", read.entries.isEmpty())
            assertEquals(1, read.rejectedCount)
        }

    /** invalid_kind_access_combo_rejected: an access outside {public, private} is a typed reject on read. */
    @Test
    fun invalid_kind_access_combo_rejected() =
        runTest {
            val key = ChatDirectoryTestSupport.communityKey()
            val identity = ChatDirectoryTestSupport.newIdentity()
            // A valid group kind but an out-of-enum access byte (0x09) — must be dropped on read.
            val bad =
                ChatDirectoryTestSupport.sealRawDescriptor(
                    identity = identity,
                    chatId = "chat-bad-access".toByteArray(),
                    kindByte = ChatDescriptorFormat.KIND_GROUP,
                    accessByte = 0x09,
                    title = "Bad Access",
                    versionCounter = 1,
                    communityKey = key,
                )
            disk.putFile(ChatDirectoryPaths.entryPath(bad.name), bad.bytes)

            val read = ChatDirectoryTestSupport.service(server).readChatDirectory(key)
            assertTrue(read.entries.isEmpty())
            assertEquals(1, read.rejectedCount)
            // At the codec layer the reason is INVALID_ACCESS (reject-don't-guess).
            val codec = ChatDirectoryTestSupport.codec()
            val opened = ChatDirectoryTestSupport.messageCrypto().openEnvelope(key, bad.bytes)
            opened as org.openwebdav.messenger.crypto.OpenResult.Opened
            assertEquals(ChatRejectReason.INVALID_ACCESS, (codec.parse(opened.bytes) as ChatParseResult.Rejected).reason)
        }

    /** read_without_community_key_yields_nothing_readable: a wrong community key drops the entry, no crash. */
    @Test
    fun read_without_community_key_yields_nothing_readable() =
        runTest {
            val realKey = ChatDirectoryTestSupport.communityKey(seed = 1)
            val wrongKey = ChatDirectoryTestSupport.communityKey(seed = 2)
            val identity = ChatDirectoryTestSupport.newIdentity()
            ChatDirectoryTestSupport.service(server)
                .publishChatEntry(identity, "chat-a".toByteArray(), ChatKind.GROUP, ChatAccess.PUBLIC, "A", 1, realKey)

            val read = ChatDirectoryTestSupport.service(server).readChatDirectory(wrongKey)
            assertTrue("no entry readable with the wrong key", read.entries.isEmpty())
            assertEquals(1, read.rejectedCount)
        }

    /**
     * tampered_chat_entry_rejected: an entry whose ciphertext is flipped by one byte is dropped (AEAD
     * reject), and another valid entry still reads.
     */
    @Test
    fun tampered_chat_entry_rejected_other_entries_still_read() =
        runTest {
            val key = ChatDirectoryTestSupport.communityKey()
            val service = ChatDirectoryTestSupport.service(server)
            service.publishChatEntry(
                ChatDirectoryTestSupport.newIdentity(),
                "chat-valid".toByteArray(),
                ChatKind.GROUP,
                ChatAccess.PUBLIC,
                "Valid",
                1,
                key,
            )
            // A tampered entry: flip a ciphertext byte, store it under the RECOMPUTED content name so the
            // §3/§11.6 on-read hash check passes and the AEAD Poly1305 tag is the defence that rejects it.
            val sealed =
                ChatDirectoryTestSupport.sealDescriptor(
                    ChatDirectoryTestSupport.newIdentity(),
                    "chat-tampered".toByteArray(),
                    ChatAccess.PUBLIC,
                    "Tampered",
                    1,
                    key,
                )
            val flipped = sealed.bytes.copyOf()
            flipped[flipped.size - 1] = (flipped[flipped.size - 1].toInt() xor 0x01).toByte()
            val flippedName = ChatDirectoryPaths.entryName(flipped)
            disk.putFile(ChatDirectoryPaths.entryPath(flippedName), flipped)

            val read = service.readChatDirectory(key)
            assertEquals(listOf("Valid"), read.entries.map { it.title })
            assertEquals(1, read.rejectedCount)
        }

    /**
     * wrong_signature_rejected: an entry whose signature does not match its carried signing key is
     * dropped (hard reject on libsodium -1).
     */
    @Test
    fun wrong_signature_rejected() {
        val identity = ChatDirectoryTestSupport.newIdentity()
        val impostor = ChatDirectoryTestSupport.newIdentity()
        val codec = ChatDirectoryTestSupport.codec()
        // Serialize an inner payload that CLAIMS `identity`'s signing key but is signed by `impostor`.
        val forged =
            codec.signAndSerialize(
                chatId = "chat-x".toByteArray(),
                kind = ChatKind.GROUP,
                access = ChatAccess.PUBLIC,
                title = "Impostor",
                signingPublic = identity.copySignPublic(),
                versionCounter = 1,
                signingSecret = impostor.copySignSecret(),
            )
        assertTrue(codec.parse(forged) is ChatParseResult.Rejected)
        assertEquals(ChatRejectReason.BAD_SIGNATURE, (codec.parse(forged) as ChatParseResult.Rejected).reason)
    }

    /**
     * malformed_or_truncated_chat_entry_rejected: garbage / truncated / wrong-magic / wrong-version
     * entry blobs are typed rejections, not bounds errors or crashes — at the codec layer and through
     * the full read.
     */
    @Test
    fun malformed_or_truncated_chat_entry_rejected() =
        runTest {
            val codec = ChatDirectoryTestSupport.codec()
            // Empty / too-short payloads → MALFORMED, never a crash.
            assertTrue(codec.parse(ByteArray(0)) is ChatParseResult.Rejected)
            assertTrue(codec.parse(ByteArray(10)) is ChatParseResult.Rejected)
            assertTrue(codec.parse(ByteArray(ChatDescriptorFormat.MIN_PAYLOAD_BYTES - 1)) is ChatParseResult.Rejected)

            // An unknown chat-entry-version is a reject-don't-guess (§11.3).
            val bogusVersion = ByteArray(ChatDescriptorFormat.MIN_PAYLOAD_BYTES) { 0 }
            bogusVersion[0] = 0x7F
            assertEquals(
                ChatRejectReason.UNKNOWN_VERSION,
                (codec.parse(bogusVersion) as ChatParseResult.Rejected).reason,
            )

            // A wrong-magic FILE blob through the full read path → dropped (no crash, empty result).
            val key = ChatDirectoryTestSupport.communityKey()
            disk.putFile(ChatDirectoryPaths.entryPath("a".repeat(ChatDirectoryPaths.ENTRY_NAME_LEN)), "not an envelope".toByteArray())
            val read = ChatDirectoryTestSupport.service(server).readChatDirectory(key)
            assertTrue(read.entries.isEmpty())
            assertEquals(1, read.rejectedCount)
        }

    /**
     * An over-cap chat-id or title arriving FROM disk is a typed reject (not a throw): the codec rejects
     * an over-cap length prefix. (The publish path guards the cap with require, so this exercises the
     * read-side defence against a hand-crafted over-cap entry.)
     */
    @Test
    fun over_cap_chat_id_rejected_on_read() {
        val codec = ChatDirectoryTestSupport.codec()
        val identity = ChatDirectoryTestSupport.newIdentity()
        val tooLong = ByteArray(ChatDescriptorFormat.MAX_CHAT_ID_BYTES + 1) { 'x'.code.toByte() }
        val raw =
            ChatDirectoryTestSupport.sealRawDescriptor(
                identity = identity,
                chatId = tooLong,
                kindByte = ChatDescriptorFormat.KIND_GROUP,
                accessByte = ChatDescriptorFormat.ACCESS_PUBLIC,
                title = "T",
                versionCounter = 1,
                communityKey = ChatDirectoryTestSupport.communityKey(),
            )
        // Open + parse the inner payload directly: over-cap chat-id-len → MALFORMED.
        val opened = ChatDirectoryTestSupport.messageCrypto().openEnvelope(ChatDirectoryTestSupport.communityKey(), raw.bytes)
        opened as org.openwebdav.messenger.crypto.OpenResult.Opened
        assertEquals(ChatRejectReason.MALFORMED, (codec.parse(opened.bytes) as ChatParseResult.Rejected).reason)
        // And the secret signing key never appears in the sealed bytes (sanity).
        assertFalse(containsSub(raw.bytes, identity.copySignSecret()))
    }

    private fun containsSub(
        haystack: ByteArray,
        needle: ByteArray,
    ): Boolean {
        if (needle.isEmpty() || needle.size > haystack.size) return false
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }
}
