package org.openwebdav.messenger.chatdirectory

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §11 chat-directory publish/read round-trip + supersede + multi-chat tests
 * (`docs/features/chat-directory_plan.md` Test plan). Real libsodium-backed crypto + a
 * MockWebServer-backed transport over [ChatDirectoryFakeDisk] — the full publish → read → verify path
 * off-device. Mirrors `directory/DirectoryRoundTripTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ChatDirectoryRoundTripTest {
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

    /** publish_then_read_roundtrips_verified_chat_entry: the verified entry carries the same chat-id, kind, access, title. */
    @Test
    fun publish_then_read_roundtrips_verified_chat_entry() =
        runTest {
            val key = ChatDirectoryTestSupport.communityKey()
            val identity = ChatDirectoryTestSupport.newIdentity()
            val service = ChatDirectoryTestSupport.service(server)
            val chatId = "chat-alpha".toByteArray()

            val outcome =
                service.publishChatEntry(identity, chatId, ChatKind.GROUP, ChatAccess.PUBLIC, "Alpha Room", 1, key)
            assertTrue(outcome is ChatPublishOutcome.Published)

            val read = service.readChatDirectory(key)
            assertEquals(1, read.entries.size)
            val entry = read.entries.single()
            assertArrayEquals(chatId, entry.copyChatId())
            assertEquals(ChatKind.GROUP, entry.kind)
            assertEquals(ChatAccess.PUBLIC, entry.access)
            assertEquals("Alpha Room", entry.title)
            assertArrayEquals(identity.copySignPublic(), entry.copyPublishedBySigningKey())
            assertEquals(0, read.rejectedCount)
        }

    /**
     * chat_entry_is_ciphertext_only_on_disk: the bytes on the disk contain neither the chat-id, the
     * title, nor the kind/access in cleartext (only AEAD ciphertext + the §5 public framing).
     */
    @Test
    fun chat_entry_is_ciphertext_only_on_disk() =
        runTest {
            val key = ChatDirectoryTestSupport.communityKey()
            val identity = ChatDirectoryTestSupport.newIdentity()
            val service = ChatDirectoryTestSupport.service(server)
            val chatId = "secret-chat-id".toByteArray()

            val outcome =
                service.publishChatEntry(identity, chatId, ChatKind.GROUP, ChatAccess.PRIVATE, "SecretTitle", 1, key)
                    as ChatPublishOutcome.Published
            val onDisk = disk.bytesOf(ChatDirectoryPaths.entryPath(outcome.entryName))!!

            assertFalse("chat-id must not be on disk in cleartext", containsSub(onDisk, chatId))
            assertFalse("title must not be on disk in cleartext", containsSub(onDisk, "SecretTitle".toByteArray()))
            // The signing public key (a §11.3 field) is inside the AEAD seal — not in cleartext.
            assertFalse("signing pubkey must not be on disk in cleartext", containsSub(onDisk, identity.copySignPublic()))
        }

    /** updated_chat_entry_supersedes_older: two valid descriptors for the same chat-id → only the newer is returned. */
    @Test
    fun updated_chat_entry_supersedes_older() =
        runTest {
            val key = ChatDirectoryTestSupport.communityKey()
            val identity = ChatDirectoryTestSupport.newIdentity()
            val chatId = "chat-same".toByteArray()
            // Place an older (v1, "Old Title") and a newer (v2, "New Title") descriptor for the SAME chat-id.
            val older = ChatDirectoryTestSupport.sealDescriptor(identity, chatId, ChatAccess.PUBLIC, "Old Title", 1, key)
            val newer = ChatDirectoryTestSupport.sealDescriptor(identity, chatId, ChatAccess.PRIVATE, "New Title", 2, key)
            disk.putFile(ChatDirectoryPaths.entryPath(older.name), older.bytes)
            disk.putFile(ChatDirectoryPaths.entryPath(newer.name), newer.bytes)

            val read = ChatDirectoryTestSupport.service(server).readChatDirectory(key)
            assertEquals("exactly one descriptor per chat-id", 1, read.entries.size)
            assertEquals("New Title", read.entries.single().title)
            assertEquals(ChatAccess.PRIVATE, read.entries.single().access)
        }

    /** multiple_chats_listed: descriptors for several distinct chat-ids all read back as distinct entries. */
    @Test
    fun multiple_chats_listed() =
        runTest {
            val key = ChatDirectoryTestSupport.communityKey()
            val service = ChatDirectoryTestSupport.service(server)
            service.publishChatEntry(
                ChatDirectoryTestSupport.newIdentity(),
                "chat-a".toByteArray(),
                ChatKind.GROUP,
                ChatAccess.PUBLIC,
                "A",
                1,
                key,
            )
            service.publishChatEntry(
                ChatDirectoryTestSupport.newIdentity(),
                "chat-b".toByteArray(),
                ChatKind.GROUP,
                ChatAccess.PRIVATE,
                "B",
                1,
                key,
            )
            service.publishChatEntry(
                ChatDirectoryTestSupport.newIdentity(),
                "chat-c".toByteArray(),
                ChatKind.GROUP,
                ChatAccess.PUBLIC,
                "C",
                1,
                key,
            )

            val read = service.readChatDirectory(key)
            assertEquals(3, read.entries.size)
            assertEquals(setOf("A", "B", "C"), read.entries.map { it.title }.toSet())
            // All three chat-ids are distinct.
            assertEquals(3, read.entries.map { it.copyChatId().toList() }.toSet().size)
        }

    /**
     * private_group_listed_without_key: a private-group descriptor reads back with access = private and
     * its title, and the verified entry carries NO content key (only the metadata + the author's pubkey).
     */
    @Test
    fun private_group_listed_without_key() =
        runTest {
            val key = ChatDirectoryTestSupport.communityKey()
            val identity = ChatDirectoryTestSupport.newIdentity()
            val service = ChatDirectoryTestSupport.service(server)
            service.publishChatEntry(identity, "private-chat".toByteArray(), ChatKind.GROUP, ChatAccess.PRIVATE, "Private Room", 1, key)

            val read = service.readChatDirectory(key)
            val entry = read.entries.single()
            assertEquals(ChatAccess.PRIVATE, entry.access)
            assertEquals("Private Room", entry.title)
            // The verified record's only key is the AUTHOR's public signing key — never a chat content key.
            assertArrayEquals(identity.copySignPublic(), entry.copyPublishedBySigningKey())
        }

    /** Idempotent re-publish of identical bytes lands at the same content-addressed name (§11.4). */
    @Test
    fun identical_republish_is_idempotent_same_name() =
        runTest {
            val key = ChatDirectoryTestSupport.communityKey()
            val identity = ChatDirectoryTestSupport.newIdentity()
            val sealed = ChatDirectoryTestSupport.sealDescriptor(identity, "chat-x".toByteArray(), ChatAccess.PUBLIC, "X", 1, key)
            disk.putFile(ChatDirectoryPaths.entryPath(sealed.name), sealed.bytes)
            disk.putFile(ChatDirectoryPaths.entryPath(sealed.name), sealed.bytes)

            val read = ChatDirectoryTestSupport.service(server).readChatDirectory(key)
            assertEquals(1, read.entries.size)
            assertEquals(ChatDirectoryPaths.ENTRY_NAME_LEN, sealed.name.length)
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
