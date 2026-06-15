package org.openwebdav.messenger.sync

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.data.MessageStore
import org.openwebdav.messenger.data.MessengerDatabase
import org.openwebdav.messenger.identity.Identity
import org.openwebdav.messenger.message.MessageEnvelope
import org.openwebdav.messenger.protocol.ChatPaths
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * Interaction-scenario tests (`docs/features/sync_plan.md` → Interaction scenarios / Test plan): the
 * cycle is robust to tampered/incomplete files, transport back-off, and Doze deferral, and loads the
 * chat key once per cycle. Real envelopes + [FakeDisk] + in-memory Room.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PollInteractionTest {
    private lateinit var server: MockWebServer
    private lateinit var disk: FakeDisk
    private lateinit var db: MessengerDatabase
    private lateinit var store: MessageStore
    private lateinit var sender: Identity
    private val key: ChatKey = SyncTestSupport.fixedChatKey()
    private val envelope: MessageEnvelope = SyncTestSupport.messageEnvelope()
    private val me = "bob"
    private val chatId = SyncTestSupport.CHAT_ID
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
        SyncEngine(SyncTestSupport.transport(server), envelope, store, keyProvider, clock = { 1L })

    private fun publish(
        body: String,
        seq: Long,
        ts: Long = 1_717_000_000_000L,
    ): SyncTestSupport.SealedEntry {
        val entry = SyncTestSupport.sealedLogEntry(SyncTestSupport.text(sender, body = body), key, sender, "alice", ts = ts, seq = seq)
        disk.putFile(ChatPaths.message(chatId, entry.orderToken, entry.bytes), entry.bytes)
        val indexPath = ChatPaths.changeIndex(me, SyncTestSupport.CHAT_ID)
        disk.putFile("$indexPath/${SyncTestSupport.changeEntryName(SyncTestSupport.CHAT_ID, entry.orderToken)}", byteArrayOf(0))
        return entry
    }

    /** tampered_file_skipped / forged_or_tampered_file_rejected_cycle_continues. */
    @Test
    fun `tampered file is skipped and the cycle continues for the rest`() =
        runTest {
            val good1 = publish("good1", seq = 1, ts = 1_000_000_000_000L)
            // A tampered file: valid §2 name but bytes that do not match the hash → transport NotReady
            // → skipped. We forge a name with a valid grammar but garbage body under the SAME log/.
            val tamperedName = good1.name.dropLast(1) + (if (good1.name.last() == 'a') 'b' else 'a')
            disk.putFile("${ChatPaths.logDir(chatId)}/$tamperedName", byteArrayOf(1, 2, 3, 4, 5))
            val indexPath = ChatPaths.changeIndex(me, SyncTestSupport.CHAT_ID)
            val tamperedToken = org.openwebdav.messenger.protocol.MessageId.splitMessageId(tamperedName)!!.first
            disk.putFile("$indexPath/${SyncTestSupport.changeEntryName(SyncTestSupport.CHAT_ID, tamperedToken)}", byteArrayOf(0))
            val good2 = publish("good2", seq = 2, ts = 2_000_000_000_000L)

            val outcome = engine().pollCycle(me, sub)

            assertEquals(2, outcome.newCount) // both good messages stored
            assertTrue(outcome.skippedCount >= 1) // tampered skipped
            val bodies = store.messagesForChat(SyncTestSupport.CHAT_ID).map { it.body }
            assertEquals(listOf("good1", "good2"), bodies)
            assertEquals(good2.orderToken, store.cursorFor(SyncTestSupport.CHAT_ID))
        }

    /** A file decrypted under the WRONG key → AEAD reject → skipped, cycle continues. */
    @Test
    fun `file under wrong key is rejected and skipped`() =
        runTest {
            publish("readable", seq = 1, ts = 1_000_000_000_000L)
            // an entry sealed under a DIFFERENT key, listed in the index
            val otherKey = SyncTestSupport.fixedChatKey(seed = 99)
            val foreign =
                SyncTestSupport.sealedLogEntry(
                    SyncTestSupport.text(sender, body = "secret"),
                    otherKey,
                    sender,
                    "alice",
                    ts = 2_000_000_000_000L,
                    seq = 2,
                )
            disk.putFile(ChatPaths.message(chatId, foreign.orderToken, foreign.bytes), foreign.bytes)
            val indexPath = ChatPaths.changeIndex(me, SyncTestSupport.CHAT_ID)
            disk.putFile("$indexPath/${SyncTestSupport.changeEntryName(SyncTestSupport.CHAT_ID, foreign.orderToken)}", byteArrayOf(0))

            val outcome = engine().pollCycle(me, sub)

            assertEquals(1, outcome.newCount) // only the readable one
            assertEquals(1, outcome.skippedCount) // the wrong-key one rejected by AEAD
            assertEquals(listOf("readable"), store.messagesForChat(SyncTestSupport.CHAT_ID).map { it.body })
        }

    /** cursor_not_advanced_past_unfetched_on_backoff / backoff_preserves_cursor. */
    @Test
    fun `429 mid-fetch does not advance cursor past unfetched entries`() =
        runTest {
            val first = publish("m1", seq = 1, ts = 1_000_000_000_000L)
            val second = publish("m2", seq = 2, ts = 2_000_000_000_000L)
            // Force the GET of m2 to 429 → the cycle backs off AFTER persisting m1, BEFORE m2.
            disk.failGet["${ChatPaths.logDir(chatId)}/${second.name}"] = 429

            val outcome = engine().pollCycle(me, sub)

            assertTrue(outcome.backedOff)
            assertEquals(1, outcome.newCount) // m1 persisted
            // cursor advanced to m1 ONLY — not past the unfetched m2
            assertEquals(first.orderToken, store.cursorFor(SyncTestSupport.CHAT_ID))

            // Clear the failure and re-run: resume from m1's cursor, fetch m2, no loss / no dup.
            disk.failGet.clear()
            val resume = engine().pollCycle(me, sub)
            assertEquals(1, resume.newCount)
            assertEquals(listOf("m1", "m2"), store.messagesForChat(SyncTestSupport.CHAT_ID).map { it.body })
            assertEquals(second.orderToken, store.cursorFor(SyncTestSupport.CHAT_ID))
        }

    /** doze_deferred_cycle_resumes_from_cursor: a long gap then a single cycle catches up the window. */
    @Test
    fun `cycle resumes from stored cursor after a long deferral`() =
        runTest {
            // Two cycles separated by "hours" of deferral; the cursor is the durable resume point.
            publish("before", seq = 1, ts = 1_000_000_000_000L)
            engine().pollCycle(me, sub)
            // ... Doze defers the next run for hours; meanwhile more messages accumulate ...
            publish("after1", seq = 2, ts = 5_000_000_000_000L)
            publish("after2", seq = 3, ts = 6_000_000_000_000L)

            val outcome = engine().pollCycle(me, sub)

            assertEquals(2, outcome.newCount) // catches up everything since the cursor, nothing lost
            assertEquals(listOf("before", "after1", "after2"), store.messagesForChat(SyncTestSupport.CHAT_ID).map { it.body })
        }

    /** key_derived_once_per_cycle / chat_key_not_rederived_per_message. */
    @Test
    fun `chat key is loaded once per chat not per message`() =
        runTest {
            publish("a", seq = 1, ts = 1_000_000_000_000L)
            publish("b", seq = 2, ts = 2_000_000_000_000L)
            publish("c", seq = 3, ts = 3_000_000_000_000L)
            val calls = AtomicInteger(0)
            val counting =
                ChatKeyProvider {
                    calls.incrementAndGet()
                    key
                }

            val outcome = engine(counting).pollCycle(me, sub)

            assertEquals(3, outcome.newCount)
            // ONE key load for the whole chat's three messages — NOT one per message (scenario 7).
            assertEquals(1, calls.get())
        }
}
