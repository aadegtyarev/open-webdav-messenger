package org.openwebdav.messenger.sync

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.data.MessageStore
import org.openwebdav.messenger.data.MessengerDatabase
import org.openwebdav.messenger.message.MessageEnvelope
import org.openwebdav.messenger.protocol.ChatPaths
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The two **concurrent-state** interaction-scenario tests the plan names against the generation-2
 * shared `log/` (`docs/features/sync_plan.md` → Interaction scenarios / Test plan line 84;
 * (transient `.ai-dev/arch/sync_arch.md`, deleted after ship) "Concurrent writers / torn reads"; `docs/protocol/webdav-layout.md` §3):
 *
 *  - [`concurrent_send_during_poll_no_torn_read`][concurrent send during poll sees a whole file or
 *    none and the next cycle catches up] — a send is in flight (its two write steps, §9.1: the `log/`
 *    PUT then the change-entry notify) while a poll reads the same chat's `log/`. The poll sees either
 *    the pre- or post-write state of the shared log, never a torn/partial read of an existing file, and
 *    the next cycle picks up anything missed via the durable cursor (the core safety claim of the
 *    append-only / no-lock design — no WebDAV `LOCK`, §6 / arch note §33).
 *  - [`two_writers_no_overwrite`][two writers add distinct files to the shared log neither overwriting
 *    the other] — two members write distinct content-addressed envelopes to the **one shared** `log/`;
 *    neither overwrites the other (append-only, §3 / §9.1). This exercises the no-overwrite property at
 *    the **sync seam** on the generation-2 shared log, where the predating
 *    `transport/WebDavInteractionTest.concurrent_writers_distinct_names_no_overwrite` only covered the
 *    underlying primitive against the v1 `inbox/` path.
 *
 * The interleaving is modelled deterministically over [FakeDisk] (a single-threaded in-memory WebDAV
 * disk): a send's two write steps are run as two real engine calls, and a poll cycle is run **between**
 * them (the staged intermediate state) and **after** them (the completed state) — so every observable
 * point of the concurrency is asserted, with real libsodium envelopes via [SyncTestSupport].
 */
@RunWith(RobolectricTestRunner::class)
// SDK pinned to 34 via app/src/test/resources/robolectric.properties (Robolectric 4.13 tops out at
// SDK 34; the app targetSdk is 35). The sync logic is SDK-agnostic (no SDK-35-only API used).
@Config(manifest = Config.NONE)
class ConcurrentInteractionTest {
    private lateinit var server: MockWebServer
    private lateinit var disk: FakeDisk
    private lateinit var db: MessengerDatabase
    private lateinit var store: MessageStore
    private val key: ChatKey = SyncTestSupport.fixedChatKey()
    private val envelope: MessageEnvelope = SyncTestSupport.messageEnvelope()
    private val me = "bob"
    private val chatId = SyncTestSupport.CHAT_ID
    private val sub = listOf(ChatSubscription(chatId))

    @Before
    fun setUp() {
        server = MockWebServer()
        disk = FakeDisk()
        server.dispatcher = disk
        server.start()
        db = SyncTestSupport.inMemoryDb()
        store = SyncTestSupport.store(db)
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    private fun engine(): SyncEngine =
        SyncEngine(
            transport = SyncTestSupport.transport(server),
            envelope = envelope,
            store = store,
            keyProvider = ChatKeyProvider { key },
            clock = { 7L },
        )

    /**
     * `concurrent_send_during_poll_no_torn_read` — a send writing to a chat's shared `log/` while a poll
     * reads the same log. The poll sees either the pre- or post-write state (no torn read of an existing
     * file), and the next cycle picks up anything missed via the cursor.
     *
     * Modelled deterministically: alice's send is split at its §9.1 step boundary. We run a poll at two
     * staged points — (1) AFTER the `log/` PUT but BEFORE the change-entry notify (the "in-flight"
     * state), and (2) after the full send completes — and assert: nothing is ever read torn, and across
     * the cycles the message lands exactly once via the cursor.
     */
    @Test
    fun `concurrent send during poll sees a whole file or none and the next cycle catches up`() =
        runTest {
            val alice = SyncTestSupport.newIdentity()
            val entry = SyncTestSupport.sealedLogEntry(SyncTestSupport.text(alice, body = "hello"), key, alice, "alice")
            val logPath = ChatPaths.message(chatId, entry.orderToken, entry.bytes)

            // ── Stage 1: alice's send has completed ONLY the log/ PUT (§9.1 step 1); the change-entry
            // notify to bob (§9.1 step 2) has NOT happened yet. This is the in-flight intermediate state.
            disk.putFile(logPath, entry.bytes)

            // bob polls right now. His change index is still empty (notify not yet written), so the
            // §9.3 full-log fallback runs and reads log/ directly. Whatever it reads is a WHOLE file
            // (append-only content-addressing → no torn read of an existing file): the message either
            // validates and lands, or — had the log entry not been visible yet — nothing would land.
            // Here the whole log/ file is present, so it lands; the read is never partial.
            val midSend = engine().pollCycle(me, sub)
            assertEquals(0, midSend.skippedCount) // the existing log/ file read whole — never torn/NotReady
            assertEquals(1, midSend.newCount) // saw the post-log-write state: one whole message
            assertEquals(
                listOf("hello"),
                store.messagesForChat(SyncTestSupport.CHAT_ID).map { it.body },
            )
            assertEquals(entry.orderToken, store.cursorFor(SyncTestSupport.CHAT_ID))

            // ── Stage 2: alice's send now completes — the change entry to bob lands (§9.1 step 2).
            val outcome = engine().send(SyncTestSupport.CHAT_ID, entry.orderToken, entry.bytes, listOf("alice", "bob"), "alice")
            assertTrue(outcome.complete)

            // bob's NEXT cycle now also sees the change entry. The cursor is durable, so the already
            // -fetched message is deduped (§9.3 step 3) — no double-surface, no torn read, no loss.
            val nextCycle = engine().pollCycle(me, sub)
            assertEquals(0, nextCycle.newCount) // cursor + message-id dedup → nothing new
            assertEquals(1, store.messagesForChat(SyncTestSupport.CHAT_ID).size) // exactly once
            assertEquals(entry.orderToken, store.cursorFor(SyncTestSupport.CHAT_ID))
        }

    /**
     * `two_writers_no_overwrite` (at the sync seam, generation-2 shared `log/`) — two members write
     * distinct content-addressed files to the same shared `log/`; assert neither overwrites the other
     * and both land (append-only, `docs/protocol/webdav-layout.md` §3 / §9.1).
     *
     * Each member runs a real [SyncEngine.send] (distinct senders, distinct order-tokens → distinct §2
     * content-addressed names) into the ONE shared chat log. Both files are then independently fetched
     * and decrypted by a poll — proving no overwrite happened on the shared log at the sync layer.
     */
    @Test
    fun `two writers add distinct files to the shared log neither overwriting the other`() =
        runTest {
            val alice = SyncTestSupport.newIdentity()
            val carol = SyncTestSupport.newIdentity()
            // Distinct senders + distinct order-tokens (different sender-tag and ts) → distinct §2 names.
            val fromAlice =
                SyncTestSupport.sealedLogEntry(
                    SyncTestSupport.text(alice, body = "from-alice"),
                    key,
                    alice,
                    "alice",
                    ts = 1_000_000_000_000L,
                    seq = 1,
                )
            val fromCarol =
                SyncTestSupport.sealedLogEntry(
                    SyncTestSupport.text(carol, body = "from-carol"),
                    key,
                    carol,
                    "carol",
                    ts = 2_000_000_000_000L,
                    seq = 1,
                )
            assertNotEquals(
                "two distinct writers must mint distinct content-addressed §2 names on the shared log",
                ChatPaths.message(chatId, fromAlice.orderToken, fromAlice.bytes),
                ChatPaths.message(chatId, fromCarol.orderToken, fromCarol.bytes),
            )
            val members = listOf("alice", "bob", "carol")

            // Both members send into the SAME shared log/ (one chat-root). Roster includes bob (the
            // reader) so each send notifies bob's change index — both coordinates reach the poll below.
            val aliceOutcome = engine().send(SyncTestSupport.CHAT_ID, fromAlice.orderToken, fromAlice.bytes, members, "alice")
            val carolOutcome = engine().send(SyncTestSupport.CHAT_ID, fromCarol.orderToken, fromCarol.bytes, members, "carol")
            assertTrue(aliceOutcome.complete)
            assertTrue(carolOutcome.complete)

            // Both distinct content-addressed files are present in the ONE shared log/ — neither
            // overwrote the other (append-only on the generation-2 shared log, §3 / §9.1).
            assertEquals(2, disk.fileNames(ChatPaths.logDir(chatId)).size)
            assertTrue(disk.has(ChatPaths.message(chatId, fromAlice.orderToken, fromAlice.bytes)))
            assertTrue(disk.has(ChatPaths.message(chatId, fromCarol.orderToken, fromCarol.bytes)))

            // Both land and decrypt independently for a reader — the shared log holds both messages.
            val outcome = engine().pollCycle(me, sub)
            assertEquals(2, outcome.newCount)
            assertEquals(0, outcome.skippedCount)
            // Bodies are ordered by order-token sort (§4): alice's ts < carol's ts.
            assertEquals(
                listOf("from-alice", "from-carol"),
                store.messagesForChat(SyncTestSupport.CHAT_ID).map { it.body },
            )
        }
}
