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
import org.openwebdav.messenger.directory.DirectoryPaths
import org.openwebdav.messenger.protocol.ChatPaths
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §11 chat-directory interaction-scenario tests — one per Interaction scenario in
 * `docs/features/chat-directory_plan.md`. The chat directory shares the disk + the one shared credential
 * with the §10 user `directory/` and the `sync` per-chat folders (flat trust), so these assert the
 * cross-feature, concurrency, and DM-drop behaviors.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ChatDirectoryInteractionTest {
    private val chatId = "chat-1"
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

    /** concurrent_publishes_same_chat_both_land: two members publish for the same chat-id; both land, latest wins. */
    @Test
    fun concurrent_publishes_same_chat_both_land() =
        runTest {
            val key = ChatDirectoryTestSupport.communityKey()
            val service = ChatDirectoryTestSupport.service(server)
            val chatId = "shared-chat".toByteArray()
            val alice = ChatDirectoryTestSupport.newIdentity()
            val bob = ChatDirectoryTestSupport.newIdentity()
            // Each publish is a distinct content-addressed file (§11.4) — no overwrite/collision — and
            // under flat trust BOTH members may publish for the same chat-id; the latest version wins.
            val older = ChatDirectoryTestSupport.sealDescriptor(alice, chatId, ChatAccess.PUBLIC, "Alice's name", 1, key)
            val newer = ChatDirectoryTestSupport.sealDescriptor(bob, chatId, ChatAccess.PUBLIC, "Bob's name", 2, key)
            disk.putFile(ChatDirectoryPaths.entryPath(older.name), older.bytes)
            disk.putFile(ChatDirectoryPaths.entryPath(newer.name), newer.bytes)
            assertFalse("two publishes have distinct names", older.name == newer.name)

            val read = service.readChatDirectory(key)
            assertEquals("one descriptor per chat-id, latest version", 1, read.entries.size)
            assertEquals("Bob's name", read.entries.single().title)
        }

    /** publish_during_read_picked_up_next_read: an entry written after a read is absent then present. */
    @Test
    fun publish_during_read_picked_up_next_read() =
        runTest {
            val key = ChatDirectoryTestSupport.communityKey()
            val service = ChatDirectoryTestSupport.service(server)
            service.publishChatEntry(
                ChatDirectoryTestSupport.newIdentity(),
                "chat-1".toByteArray(),
                ChatKind.GROUP,
                ChatAccess.PUBLIC,
                "First",
                1,
                key,
            )

            val firstRead = service.readChatDirectory(key)
            assertEquals(listOf("First"), firstRead.entries.map { it.title })

            service.publishChatEntry(
                ChatDirectoryTestSupport.newIdentity(),
                "chat-2".toByteArray(),
                ChatKind.GROUP,
                ChatAccess.PUBLIC,
                "Second",
                1,
                key,
            )
            val secondRead = service.readChatDirectory(key)
            assertEquals(setOf("First", "Second"), secondRead.entries.map { it.title }.toSet())
        }

    /** tampered_chat_entry_does_not_wedge_directory: one forged entry is dropped; the rest still read. */
    @Test
    fun tampered_chat_entry_does_not_wedge_directory() =
        runTest {
            val key = ChatDirectoryTestSupport.communityKey()
            val service = ChatDirectoryTestSupport.service(server)
            service.publishChatEntry(
                ChatDirectoryTestSupport.newIdentity(),
                "chat-a".toByteArray(),
                ChatKind.GROUP,
                ChatAccess.PUBLIC,
                "Alpha",
                1,
                key,
            )
            service.publishChatEntry(
                ChatDirectoryTestSupport.newIdentity(),
                "chat-b".toByteArray(),
                ChatKind.GROUP,
                ChatAccess.PUBLIC,
                "Beta",
                1,
                key,
            )
            // A garbage file in the same collection (well-formed name, junk bytes).
            disk.putFile(ChatDirectoryPaths.entryPath("z".repeat(ChatDirectoryPaths.ENTRY_NAME_LEN)), ByteArray(64) { 0xAB.toByte() })

            val read = service.readChatDirectory(key)
            assertEquals("the two valid entries still read", setOf("Alpha", "Beta"), read.entries.map { it.title }.toSet())
            assertEquals(1, read.rejectedCount)
        }

    /** forged_dm_entry_dropped_others_read: a forged dm-kind entry is dropped while valid group entries read. */
    @Test
    fun forged_dm_entry_dropped_others_read() =
        runTest {
            val key = ChatDirectoryTestSupport.communityKey()
            val service = ChatDirectoryTestSupport.service(server)
            service.publishChatEntry(
                ChatDirectoryTestSupport.newIdentity(),
                "group-chat".toByteArray(),
                ChatKind.GROUP,
                ChatAccess.PUBLIC,
                "Group",
                1,
                key,
            )
            // A validly-sealed-and-signed dm entry forged onto the collection — must be dropped on read.
            val forgedDm =
                ChatDirectoryTestSupport.sealRawDescriptor(
                    identity = ChatDirectoryTestSupport.newIdentity(),
                    chatId = "dm-chat".toByteArray(),
                    kindByte = ChatDescriptorFormat.KIND_DM,
                    accessByte = ChatDescriptorFormat.ACCESS_PRIVATE,
                    title = "Forged DM",
                    versionCounter = 1,
                    communityKey = key,
                )
            disk.putFile(ChatDirectoryPaths.entryPath(forgedDm.name), forgedDm.bytes)

            val read = service.readChatDirectory(key)
            assertEquals("only the group entry surfaces", listOf("Group"), read.entries.map { it.title })
            assertEquals(1, read.rejectedCount)
        }

    /** updated_descriptor_resolves_latest_per_chat_id: older + newer for one chat-id → exactly the newer. */
    @Test
    fun updated_descriptor_resolves_latest_per_chat_id() =
        runTest {
            val key = ChatDirectoryTestSupport.communityKey()
            val identity = ChatDirectoryTestSupport.newIdentity()
            val chatId = "evolving-chat".toByteArray()
            val older = ChatDirectoryTestSupport.sealDescriptor(identity, chatId, ChatAccess.PUBLIC, "Old", 1, key)
            val newer = ChatDirectoryTestSupport.sealDescriptor(identity, chatId, ChatAccess.PUBLIC, "New", 2, key)
            disk.putFile(ChatDirectoryPaths.entryPath(older.name), older.bytes)
            disk.putFile(ChatDirectoryPaths.entryPath(newer.name), newer.bytes)

            val read = ChatDirectoryTestSupport.service(server).readChatDirectory(key)
            assertEquals(1, read.entries.size)
            assertEquals("New", read.entries.single().title)
        }

    /**
     * chat_directory_read_does_not_touch_user_directory_or_chat_folders: a chat-directory read/write only
     * touches `chat-directory/`, never the §10 `directory/` nor a chat's `log/`/`changes/`/`meta/`.
     */
    @Test
    fun chat_directory_read_does_not_touch_user_directory_or_chat_folders() =
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
            service.readChatDirectory(key)

            assertTrue(disk.fileNames(ChatDirectoryPaths.CHAT_DIRECTORY).isNotEmpty())
            assertTrue("no §10 user directory/ touched", disk.fileNames(DirectoryPaths.DIRECTORY).isEmpty())
            assertTrue("no chat log/ touched", disk.fileNames(ChatPaths.logDir(chatId)).isEmpty())
            assertTrue("no chat changes/ touched", disk.fileNames(ChatPaths.CHANGES).isEmpty())
            assertTrue("no chat meta/ touched", disk.fileNames(ChatPaths.META).isEmpty())
        }

    /** wrong_key_chat_entry_dropped_not_crash: an entry sealed with a different community key is a typed drop. */
    @Test
    fun wrong_key_chat_entry_dropped_not_crash() =
        runTest {
            val readerKey = ChatDirectoryTestSupport.communityKey(seed = 10)
            val staleKey = ChatDirectoryTestSupport.communityKey(seed = 11) // e.g. a stale key after rotation
            val service = ChatDirectoryTestSupport.service(server)
            service.publishChatEntry(
                ChatDirectoryTestSupport.newIdentity(),
                "current-chat".toByteArray(),
                ChatKind.GROUP,
                ChatAccess.PUBLIC,
                "Current",
                1,
                readerKey,
            )
            val stale =
                ChatDirectoryTestSupport.sealDescriptor(
                    ChatDirectoryTestSupport.newIdentity(),
                    "stale-chat".toByteArray(),
                    ChatAccess.PUBLIC,
                    "Stale",
                    1,
                    staleKey,
                )
            disk.putFile(ChatDirectoryPaths.entryPath(stale.name), stale.bytes)

            val read = service.readChatDirectory(readerKey)
            assertEquals(listOf("Current"), read.entries.map { it.title })
            assertEquals(1, read.rejectedCount)
        }
}
