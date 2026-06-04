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
import org.openwebdav.messenger.protocol.Envelope
import org.openwebdav.messenger.protocol.MessageId
import org.openwebdav.messenger.protocol.OrderToken
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §9.3 cursor-advance invariant (`docs/protocol/webdav-layout.md`; code-review finding 1): the cursor
 * advances ONLY over the longest contiguous **resolved** prefix of the `log/` listing. A transient
 * NotReady (incomplete upload / §3 hash-mismatch) at a fresh coordinate blocks the cursor so it is
 * retried next cycle — never skipped past by a later complete entry (the no-loss invariant). A
 * permanent Rejected (AEAD/signature fail / unsupported codec — a forged file a member can plant under
 * flat trust) does NOT wedge the cursor: a later entry's coordinate still advances.
 *
 * Also covers finding 4: an unsupported (non-0x00) codec-id is a typed reject-with-reason, not a silent
 * AEAD AAD-mismatch drop.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class CursorContiguousPrefixTest {
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

    private fun engine(): SyncEngine = SyncEngine(SyncTestSupport.transport(server), envelope, store, { key }, clock = { 1L })

    /** Seal + place a readable log entry and a change-index marker advancing the member to its coordinate. */
    private fun publish(
        body: String,
        seq: Long,
        ts: Long,
        chatKey: ChatKey = key,
    ): SyncTestSupport.SealedEntry {
        val entry = SyncTestSupport.sealedLogEntry(SyncTestSupport.text(sender, body = body), chatKey, sender, "alice", ts = ts, seq = seq)
        disk.putFile(ChatPaths.message(entry.orderToken, entry.bytes), entry.bytes)
        markChanged(entry.orderToken)
        return entry
    }

    /** Drop a change-index entry at [orderToken] so the poll's high-water coordinate covers it. */
    private fun markChanged(orderToken: String) {
        val indexPath = ChatPaths.changeIndex(me, SyncTestSupport.CHAT_ID)
        disk.putFile("$indexPath/${SyncTestSupport.changeEntryName(SyncTestSupport.CHAT_ID, orderToken)}", byteArrayOf(0))
    }

    /**
     * Finding 1 — the silent-loss scenario fixed: `[msg@002 NotReady, msg@003 persisted]`. The cursor
     * stays at the pre-002 coordinate (NOT advanced past 002 by 003 persisting), and a follow-up cycle
     * where 002 becomes Ready fetches it — no loss.
     */
    @Test
    fun `a transient not-ready entry blocks the cursor and is fetched next cycle`() =
        runTest {
            val m002 = publish("m002", seq = 1, ts = 1_000_000_000_000L)
            val m003 = publish("m003", seq = 2, ts = 2_000_000_000_000L)
            // 002's upload is still settling on the disk → its GET is not-ready (404) this cycle.
            disk.failGet["${ChatPaths.LOG}/${m002.name}"] = 404

            val first = engine().pollCycle(me, sub)

            // 003 persisted, 002 not-ready (skipped). The cursor must NOT have advanced past 002 — it
            // stays at the pre-002 coordinate so 002 is retried, not lost.
            assertEquals(1, first.newCount)
            assertTrue(first.skippedCount >= 1)
            assertEquals("", store.cursorFor(SyncTestSupport.CHAT_ID)) // pre-002 (start of window)
            assertEquals(listOf("m003"), store.messagesForChat(SyncTestSupport.CHAT_ID).map { it.body })

            // 002 becomes Ready; the next cycle re-lists from the unmoved cursor and fetches it (no loss).
            disk.failGet.clear()
            val second = engine().pollCycle(me, sub)

            assertEquals(1, second.newCount) // 002 picked up; 003 deduped by §2 id
            assertEquals(listOf("m002", "m003"), store.messagesForChat(SyncTestSupport.CHAT_ID).map { it.body })
            assertEquals(m003.orderToken, store.cursorFor(SyncTestSupport.CHAT_ID)) // now fully advanced
        }

    /**
     * Finding 1 — a permanently-Rejected (forged) low-order-token entry does NOT permanently block the
     * cursor advance of higher entries. A file sealed under the WRONG key (valid content-hash → transport
     * Ready, then AEAD-open fails → permanent Rejected) at a LOW coordinate must not wedge: a higher, good
     * entry's coordinate still advances. Otherwise a single planted forgery freezes the chat forever.
     */
    @Test
    fun `a permanently rejected forged low entry does not wedge higher entries`() =
        runTest {
            // A forged low-order file: sealed under a different key, listed in the index. Its name hashes
            // its own bytes correctly (transport Ready), but AEAD-open fails → permanent Rejected.
            val forged =
                SyncTestSupport.sealedLogEntry(
                    SyncTestSupport.text(sender, body = "forged"),
                    SyncTestSupport.fixedChatKey(seed = 99),
                    sender,
                    "alice",
                    ts = 1_000_000_000_000L,
                    seq = 1,
                )
            disk.putFile(ChatPaths.message(forged.orderToken, forged.bytes), forged.bytes)
            markChanged(forged.orderToken)
            val good = publish("good-high", seq = 2, ts = 2_000_000_000_000L)

            val first = engine().pollCycle(me, sub)

            // The forged entry is rejected (skipped) but the higher good entry persists AND its coordinate
            // advances the cursor — the forgery did not wedge the cursor at the low coordinate.
            assertEquals(1, first.newCount)
            assertTrue(first.skippedCount >= 1)
            assertEquals(listOf("good-high"), store.messagesForChat(SyncTestSupport.CHAT_ID).map { it.body })
            assertEquals(good.orderToken, store.cursorFor(SyncTestSupport.CHAT_ID))

            // A re-run does not re-surface the good message (cursor advanced past the forgery, no wedge).
            val second = engine().pollCycle(me, sub)
            assertEquals(0, second.newCount)
            assertEquals(1, store.messagesForChat(SyncTestSupport.CHAT_ID).size)
        }

    /**
     * Finding 4 — an entry whose on-disk header carries an unsupported codec-id (0x01 deflate, not wired
     * this feature) is a typed reject-with-reason, NOT a silent AEAD AAD-mismatch drop. The reader reads
     * the REAL codec-id from the header and rejects on a codec it does not support; the cycle continues
     * and the cursor advances past the (permanently) rejected entry.
     */
    @Test
    fun `an unsupported codec id is rejected with reason not silently dropped`() =
        runTest {
            // Hand-build a file with a valid §2 name but a codec-id of 0x01 (deflate) in the header, so
            // the transport's frame parse accepts it (0x01 is a DEFINED codec) and returns Ready — the
            // reader must then reject it on the unsupported codec, not silently fail AEAD.
            val plaintextBlob = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8) // opaque; never reaches AEAD
            val deflateFramed = Envelope.frame(Envelope.CODEC_DEFLATE, plaintextBlob)
            val token = OrderToken.build(1_000_000_000_000L, "alice", 7)
            val name = MessageId.messageId(token, deflateFramed) // content-hash over the real bytes → Ready
            disk.putFile("${ChatPaths.LOG}/$name", deflateFramed)
            markChanged(token)

            val outcome = engine().pollCycle(me, sub)

            assertEquals(0, outcome.newCount) // unsupported codec → not surfaced
            assertTrue(outcome.skippedCount >= 1)
            // Rejected (permanent) → the cursor advances past it (a deflate file is not a transient wedge).
            assertEquals(token, store.cursorFor(SyncTestSupport.CHAT_ID))
        }
}
