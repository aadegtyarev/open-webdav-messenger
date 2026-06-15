package org.openwebdav.messenger.directory

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.openwebdav.messenger.protocol.ChatPaths
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §10 directory interaction-scenario tests — one per Interaction scenario in
 * `docs/features/directory_plan.md`. The directory shares the disk + the one shared credential with the
 * `sync` per-chat folders (flat trust), so these assert the cross-feature and concurrency behaviors.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class DirectoryInteractionTest {
    private val chatId = "chat-1"
    private lateinit var server: MockWebServer
    private lateinit var disk: DirectoryFakeDisk

    @Before
    fun setUp() {
        server = MockWebServer()
        disk = DirectoryFakeDisk()
        server.dispatcher = disk
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** concurrent_publishes_both_land: two members publish near-simultaneously; both entries read back. */
    @Test
    fun concurrent_publishes_both_land() =
        runTest {
            val key = DirectoryTestSupport.communityKey()
            val service = DirectoryTestSupport.service(server)
            val alice = DirectoryTestSupport.newIdentity()
            val bob = DirectoryTestSupport.newIdentity()
            // Each publish is a distinct content-addressed file (§10.4) — no overwrite/collision.
            val a = service.publishEntry(alice, "Alice", 1, key) as PublishOutcome.Published
            val b = service.publishEntry(bob, "Bob", 1, key) as PublishOutcome.Published
            assertFalse("two members' entries have distinct names", a.entryName == b.entryName)

            val read = service.readDirectory(key)
            assertEquals(setOf("Alice", "Bob"), read.entries.map { it.displayName }.toSet())
        }

    /** publish_during_read_picked_up_next_read: an entry written after a read is absent then present. */
    @Test
    fun publish_during_read_picked_up_next_read() =
        runTest {
            val key = DirectoryTestSupport.communityKey()
            val service = DirectoryTestSupport.service(server)
            service.publishEntry(DirectoryTestSupport.newIdentity(), "First", 1, key)

            val firstRead = service.readDirectory(key)
            assertEquals(listOf("First"), firstRead.entries.map { it.displayName })

            // A new entry written AFTER the first read is picked up on the next read (self-contained file).
            service.publishEntry(DirectoryTestSupport.newIdentity(), "Second", 1, key)
            val secondRead = service.readDirectory(key)
            assertEquals(setOf("First", "Second"), secondRead.entries.map { it.displayName }.toSet())
        }

    /** deleted_entry_absent_until_republish: deleting a member's entry removes them; re-publish restores. */
    @Test
    fun deleted_entry_absent_until_republish() =
        runTest {
            val key = DirectoryTestSupport.communityKey()
            val service = DirectoryTestSupport.service(server)
            val identity = DirectoryTestSupport.newIdentity()
            val published = service.publishEntry(identity, "Alice", 1, key) as PublishOutcome.Published

            // Any member can DELETE any entry under the one shared credential (flat trust, SC11).
            disk.remove(DirectoryPaths.entryPath(published.entryName))
            assertTrue("absent after deletion", service.readDirectory(key).entries.isEmpty())

            // The owner re-publishes to restore their entry — no crash, present again.
            service.publishEntry(identity, "Alice", 2, key)
            val restored = service.readDirectory(key)
            assertEquals(listOf("Alice"), restored.entries.map { it.displayName })
        }

    /** tampered_entry_does_not_wedge_directory: one forged entry is dropped; the rest still read. */
    @Test
    fun tampered_entry_does_not_wedge_directory() =
        runTest {
            val key = DirectoryTestSupport.communityKey()
            val service = DirectoryTestSupport.service(server)
            // Two valid entries + one garbage file in the same collection.
            service.publishEntry(DirectoryTestSupport.newIdentity(), "Alice", 1, key)
            service.publishEntry(DirectoryTestSupport.newIdentity(), "Bob", 1, key)
            disk.putFile(DirectoryPaths.entryPath("z".repeat(DirectoryPaths.ENTRY_NAME_LEN)), ByteArray(64) { 0xAB.toByte() })

            val read = service.readDirectory(key)
            assertEquals("the two valid entries still read", setOf("Alice", "Bob"), read.entries.map { it.displayName }.toSet())
            assertEquals(1, read.rejectedCount)
        }

    /**
     * directory_read_does_not_touch_chat_folders: a directory read/write only touches `directory/`,
     * never a chat's `log/`/`changes/`/`meta/` — asserted against the on-disk paths the requests hit.
     */
    @Test
    fun directory_read_does_not_touch_chat_folders() =
        runTest {
            val key = DirectoryTestSupport.communityKey()
            val service = DirectoryTestSupport.service(server)
            service.publishEntry(DirectoryTestSupport.newIdentity(), "Alice", 1, key)
            service.readDirectory(key)

            // Every file the directory wrote sits under `directory/`; none under any chat collection.
            assertTrue(disk.fileNames(DirectoryPaths.DIRECTORY).isNotEmpty())
            assertTrue("no chat log/ touched", disk.fileNames(ChatPaths.logDir(chatId)).isEmpty())
            assertTrue("no chat changes/ touched", disk.fileNames(ChatPaths.CHANGES).isEmpty())
            assertTrue("no chat meta/ touched", disk.fileNames(ChatPaths.META).isEmpty())
        }

    /** wrong_key_entry_dropped_not_crash: an entry sealed with a different community key is a typed drop. */
    @Test
    fun wrong_key_entry_dropped_not_crash() =
        runTest {
            val readerKey = DirectoryTestSupport.communityKey(seed = 10)
            val staleKey = DirectoryTestSupport.communityKey(seed = 11) // e.g. a stale key after rotation
            val service = DirectoryTestSupport.service(server)
            // A good entry under the reader's key + an entry sealed under a different (stale) key.
            service.publishEntry(DirectoryTestSupport.newIdentity(), "Current", 1, readerKey)
            val stale = DirectoryTestSupport.sealEntry(DirectoryTestSupport.newIdentity(), "Stale", 1, staleKey)
            disk.putFile(DirectoryPaths.entryPath(stale.name), stale.bytes)

            val read = service.readDirectory(readerKey)
            assertEquals(listOf("Current"), read.entries.map { it.displayName })
            assertEquals(1, read.rejectedCount)
        }
}
