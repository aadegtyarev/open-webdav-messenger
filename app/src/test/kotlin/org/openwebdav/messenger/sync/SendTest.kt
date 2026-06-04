package org.openwebdav.messenger.sync

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
 * §9.1 send tests (`docs/features/sync_plan.md` Test plan): one shared-log write + a tiny change entry
 * per OTHER member; idempotent on the §2 message-id; partial-failure tolerant. Robolectric supplies a
 * Context (none needed for send, but the runner is shared with the Room-backed tests).
 */
@RunWith(RobolectricTestRunner::class)
// SDK pinned to 34 via app/src/test/resources/robolectric.properties (Robolectric 4.13 tops out at
// SDK 34; the app targetSdk is 35). The sync logic is SDK-agnostic (no SDK-35-only API used).
@Config(manifest = Config.NONE)
class SendTest {
    private lateinit var server: MockWebServer
    private lateinit var disk: FakeDisk

    @Before
    fun setUp() {
        server = MockWebServer()
        disk = FakeDisk()
        server.dispatcher = disk
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun engine(): SyncEngine =
        SyncEngine(
            transport = SyncTestSupport.transport(server),
            envelope = SyncTestSupport.messageEnvelope(),
            store = SyncTestSupport.store(SyncTestSupport.inMemoryDb()),
            keyProvider = { SyncTestSupport.fixedChatKey() },
        )

    /** send_writes_one_log_entry_and_updates_each_member_index. */
    @Test
    fun `send writes one log entry and one change entry per other member`() =
        runTest {
            val sender = SyncTestSupport.newIdentity()
            val entry = SyncTestSupport.sealedLogEntry(SyncTestSupport.text(sender), SyncTestSupport.fixedChatKey(), sender, "alice")
            val members = listOf("alice", "bob", "carol")

            val outcome = engine().send(SyncTestSupport.CHAT_ID, entry.orderToken, entry.bytes, members, "alice")

            assertTrue(outcome.complete)
            assertEquals(2, outcome.notifiedMembers) // bob + carol, NOT alice (sender)
            // exactly one shared-log file (no per-member full-message copy)
            assertEquals(1, disk.fileNames(ChatPaths.LOG).size)
            assertTrue(disk.has(ChatPaths.message(entry.orderToken, entry.bytes)))
            // a change entry in bob's and carol's index, none in alice's
            assertEquals(1, disk.fileNames(ChatPaths.changeIndex("bob", SyncTestSupport.CHAT_ID)).size)
            assertEquals(1, disk.fileNames(ChatPaths.changeIndex("carol", SyncTestSupport.CHAT_ID)).size)
            assertEquals(0, disk.fileNames(ChatPaths.changeIndex("alice", SyncTestSupport.CHAT_ID)).size)
        }

    /** send_is_idempotent_on_message_id: the same envelope sent twice writes the same files, no dup. */
    @Test
    fun `send is idempotent on message id`() =
        runTest {
            val sender = SyncTestSupport.newIdentity()
            val entry = SyncTestSupport.sealedLogEntry(SyncTestSupport.text(sender), SyncTestSupport.fixedChatKey(), sender, "alice")
            val members = listOf("alice", "bob")
            val engine = engine()

            engine.send(SyncTestSupport.CHAT_ID, entry.orderToken, entry.bytes, members, "alice")
            val second = engine.send(SyncTestSupport.CHAT_ID, entry.orderToken, entry.bytes, members, "alice")

            assertTrue(second.complete)
            // Content-addressed name + cursor-addressed change entry → same paths → still one each.
            assertEquals(1, disk.fileNames(ChatPaths.LOG).size)
            assertEquals(1, disk.fileNames(ChatPaths.changeIndex("bob", SyncTestSupport.CHAT_ID)).size)
        }

    /** Partial failure: a 429 on a member's change-index PUT leaves that member pending; the log is durable. */
    @Test
    fun `send tolerates partial change-index failure and is resumable`() =
        runTest {
            val sender = SyncTestSupport.newIdentity()
            val entry = SyncTestSupport.sealedLogEntry(SyncTestSupport.text(sender), SyncTestSupport.fixedChatKey(), sender, "alice")
            val members = listOf("alice", "bob", "carol")
            // Force every PUT into bob's change index to 429.
            disk.failPutUnderPrefix[ChatPaths.changeIndex("bob", SyncTestSupport.CHAT_ID)] = 429
            val engine = engine()

            val first = engine.send(SyncTestSupport.CHAT_ID, entry.orderToken, entry.bytes, members, "alice")

            assertTrue(first.logWritten) // the single log write is the durable anchor
            assertEquals(1, first.pendingMembers) // bob's notify failed
            assertEquals(1, first.notifiedMembers) // carol's succeeded
            assertFalse(first.complete)

            // Clear the failure and re-run: idempotent log re-PUT + the previously-pending notify lands.
            disk.failPutUnderPrefix.clear()
            val second = engine.send(SyncTestSupport.CHAT_ID, entry.orderToken, entry.bytes, members, "alice")
            assertTrue(second.complete)
            assertEquals(1, disk.fileNames(ChatPaths.LOG).size) // still one log copy
            assertEquals(1, disk.fileNames(ChatPaths.changeIndex("bob", SyncTestSupport.CHAT_ID)).size)
        }
}
