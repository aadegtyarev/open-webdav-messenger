package org.openwebdav.messenger.sync

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.data.MessageEntity
import org.openwebdav.messenger.data.MessageStore
import org.openwebdav.messenger.data.MessengerDatabase
import org.openwebdav.messenger.identity.Identity
import org.openwebdav.messenger.message.MessageEnvelope
import org.openwebdav.messenger.protocol.ChatPaths
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §9.3 poll-cycle tests (`docs/features/sync_plan.md` Test plan): read the change index → fetch only
 * new `log/` entries → validate/dedup/persist → advance the cursor only past persisted entries. The
 * sender side seals real envelopes; the receiver runs a real engine over a [FakeDisk] + in-memory Room.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PollCycleTest {
    private lateinit var server: MockWebServer
    private lateinit var disk: FakeDisk
    private lateinit var db: MessengerDatabase
    private lateinit var store: MessageStore
    private lateinit var sender: Identity
    private val key: ChatKey = SyncTestSupport.fixedChatKey()
    private val envelope: MessageEnvelope = SyncTestSupport.messageEnvelope()

    private val me = "bob"
    private val sub = listOf(ChatSubscription(SyncTestSupport.CHAT_ID))

    @Before
    fun setUp() {
        server = MockWebServer()
        disk = FakeDisk()
        server.dispatcher = disk
        server.start()
        db = SyncTestSupport.inMemoryDb()
        store = SyncTestSupport.store(db)
        sender = SyncTestSupport.newIdentity()
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    private fun engine(keyProvider: ChatKeyProvider = ChatKeyProvider { key }): SyncEngine =
        SyncEngine(
            transport = SyncTestSupport.transport(server),
            envelope = envelope,
            store = store,
            keyProvider = keyProvider,
            clock = { 42L },
        )

    /** Place a sealed log entry on the disk and a matching change entry in [me]'s index. */
    private fun publish(
        body: String,
        seq: Long,
        ts: Long = 1_717_000_000_000L,
        replyTo: String? = null,
    ): SyncTestSupport.SealedEntry {
        val msg = SyncTestSupport.text(sender, body = body, replyTo = replyTo)
        val entry = SyncTestSupport.sealedLogEntry(msg, key, sender, "alice", ts = ts, seq = seq)
        disk.putFile(ChatPaths.message(entry.orderToken, entry.bytes), entry.bytes)
        val indexPath = ChatPaths.changeIndex(me, SyncTestSupport.CHAT_ID)
        disk.putFile("$indexPath/${SyncTestSupport.changeEntryName(SyncTestSupport.CHAT_ID, entry.orderToken)}", byteArrayOf(0))
        return entry
    }

    /** poll_reads_index_then_fetches_only_new + room_history_is_observable. */
    @Test
    fun `poll fetches new entries and persists them`() =
        runTest {
            val a = publish("first", seq = 1)
            val b = publish("second", seq = 2)

            val outcome = engine().pollCycle(me, sub)

            assertEquals(2, outcome.newCount)
            assertEquals(0, outcome.skippedCount)
            assertTrue(!outcome.backedOff)
            val rows = store.messagesForChat(SyncTestSupport.CHAT_ID)
            assertEquals(listOf("first", "second"), rows.map { it.body })
            // cursor advanced to the newest persisted coordinate
            assertEquals(b.orderToken, store.cursorFor(SyncTestSupport.CHAT_ID))
            assertEquals(MessageEntity.KIND_TEXT, rows.first().kind)
            assertEquals(a.name, rows.first().messageId)
        }

    /** poll_dedupes_by_message_id: two consecutive cycles → exactly one row. */
    @Test
    fun `poll dedupes across cycles`() =
        runTest {
            publish("once", seq = 1)
            val engine = engine()

            val first = engine.pollCycle(me, sub)
            val second = engine.pollCycle(me, sub)

            assertEquals(1, first.newCount)
            assertEquals(0, second.newCount) // re-listed, idempotent insert → no new row
            assertEquals(1, store.messagesForChat(SyncTestSupport.CHAT_ID).size)
        }

    /** poll_orders_by_order_token: out-of-arrival-order delivery → local order = order-token sort. */
    @Test
    fun `poll orders by order token not arrival`() =
        runTest {
            // publish a LATER message first (higher ts), then an EARLIER one
            publish("later", seq = 9, ts = 2_000_000_000_000L)
            publish("earlier", seq = 1, ts = 1_000_000_000_000L)

            engine().pollCycle(me, sub)

            val bodies = store.messagesForChat(SyncTestSupport.CHAT_ID).map { it.body }
            assertEquals(listOf("earlier", "later"), bodies) // order-token sort, not arrival
        }

    /** reply_to_unreceived_target_is_stored_not_rejected. */
    @Test
    fun `reply to unreceived target is stored not rejected`() =
        runTest {
            val danglingRef = SyncTestSupport.sealedLogEntry(SyncTestSupport.text(sender), key, sender, "alice", seq = 99).name
            publish("a reply", seq = 1, replyTo = danglingRef)

            val outcome = engine().pollCycle(me, sub)

            assertEquals(1, outcome.newCount)
            val row = store.messagesForChat(SyncTestSupport.CHAT_ID).single()
            assertEquals("a reply", row.body)
            assertEquals(danglingRef, row.replyTo) // stored with the dangling reference, not dropped
        }

    /** offline_catch_up_within_window: an old cursor catches up everything newer, in order. */
    @Test
    fun `offline catch up fetches all newer than cursor`() =
        runTest {
            val first = publish("m1", seq = 1, ts = 1_000_000_000_000L)
            // simulate already having m1: set cursor to m1's coordinate
            store.advanceCursor(SyncTestSupport.CHAT_ID, first.orderToken)
            publish("m2", seq = 2, ts = 1_000_000_100_000L)
            publish("m3", seq = 3, ts = 1_000_000_200_000L)

            val outcome = engine().pollCycle(me, sub)

            assertEquals(2, outcome.newCount) // only m2 + m3 (m1 already past the cursor)
            assertEquals(listOf("m2", "m3"), store.messagesForChat(SyncTestSupport.CHAT_ID).map { it.body })
        }

    /** A chat with no change-index entry and no new log entries → nothing fetched, cursor unchanged. */
    @Test
    fun `poll with empty change index and empty log is a clean no-op`() =
        runTest {
            // change index + log both empty (nothing published) → clean no-op
            val outcome = engine().pollCycle(me, sub)
            assertEquals(0, outcome.newCount)
            assertEquals("", store.cursorFor(SyncTestSupport.CHAT_ID))
            assertNull(db.syncCursorDao().cursorFor(SyncTestSupport.CHAT_ID))
        }

    /** Sanity: a stored cursor row is readable (observable-history smoke). */
    @Test
    fun `cursor row persists after advance`() =
        runTest {
            publish("x", seq = 1)
            engine().pollCycle(me, sub)
            assertNotNull(db.syncCursorDao().cursorFor(SyncTestSupport.CHAT_ID))
        }
}
