package org.openwebdav.messenger.ui.feed

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.openwebdav.messenger.app.AppTestSupport
import org.openwebdav.messenger.app.RuntimeGraph
import org.openwebdav.messenger.crypto.Aead
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.crypto.MessageCrypto
import org.openwebdav.messenger.data.MessageStore
import org.openwebdav.messenger.data.MessengerDatabase
import org.openwebdav.messenger.identity.Identity
import org.openwebdav.messenger.message.MessageEnvelope
import org.openwebdav.messenger.message.TextMessage
import org.openwebdav.messenger.protocol.Hex
import org.openwebdav.messenger.protocol.MessageId
import org.openwebdav.messenger.protocol.OrderToken
import org.openwebdav.messenger.sync.SyncEngine
import org.openwebdav.messenger.sync.SyncTestSupport
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * JVM tests for [ChatFeedViewModel] (`ui-chat-surface` plan Test plan): the feed renders local history in
 * order from the observable Room Flow, a poll-persisted message surfaces on its own (Flow, not a blocking
 * query), and a body with Markdown / a URL renders as literal plain text (SC8 stays closed). All NEW.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ChatFeedViewModelTest {
    private val mainDispatcher = StandardTestDispatcher()
    private lateinit var server: MockWebServer
    private lateinit var db: MessengerDatabase
    private lateinit var store: MessageStore
    private lateinit var identity: Identity
    private val chatId = SyncTestSupport.CHAT_ID
    private val chatKey: ChatKey = SyncTestSupport.fixedChatKey()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        server = MockWebServer()
        server.start()
        db = SyncTestSupport.inMemoryDb()
        store = SyncTestSupport.store(db)
        identity = AppTestSupport.newIdentity()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        server.shutdown()
        db.close()
    }

    private fun graph(): RuntimeGraph {
        val envelope = MessageEnvelope.create(MessageCrypto(Aead(AppTestSupport.native())), AppTestSupport.identityCrypto())
        val engine =
            SyncEngine(
                transport = SyncTestSupport.transport(server),
                envelope = envelope,
                store = store,
                keyProvider = { chatKey },
            )
        return RuntimeGraph(
            engine = engine,
            store = store,
            envelope = envelope,
            config = SyncTestSupport.config(server),
            chatId = chatId,
            communityName = "Community",
            chatKey = chatKey,
            identity = identity,
            senderIdentifier = Hex.encode(identity.copySignPublic()),
        )
    }

    /** Persist a text message with an explicit order-token (controls feed ordering). */
    private suspend fun persistText(
        body: String,
        seq: Long,
        sender: Identity = identity,
    ) {
        val msg = TextMessage(chatId, sender.publicIdentity(), replyTo = null, body = body, sendTimestampMillis = seq)
        val orderToken = OrderToken.build(1_717_000_000_000L, Hex.encode(sender.copySignPublic()), seq)
        val messageId = MessageId.messageId(orderToken, body.toByteArray() + seq.toByte())
        store.persist(messageId, orderToken, msg, seq)
    }

    /** feed_renders_local_history_in_order — rows render oldest→newest by order-token. */
    @Test
    fun feed_renders_local_history_in_order() =
        runTest(mainDispatcher) {
            persistText("first", seq = 1)
            persistText("second", seq = 2)
            persistText("third", seq = 3)

            val vm = ChatFeedViewModel(graph())
            // An active collector starts the WhileSubscribed StateFlow; read the first populated emission.
            val rows = collectUntil(vm) { it.size >= 3 }
            assertEquals(listOf("first", "second", "third"), rows.map { it.body })
        }

    /**
     * feed_shows_message_body_as_literal_plain_text + feed_consumes_flow_not_blocking_query — a body with
     * Markdown syntax and a URL renders literally (the VM exposes the raw string; no styling/link/auto-load),
     * and the feed is read from the observable Flow (a later persist surfaces without a manual refresh).
     */
    @Test
    fun feed_shows_literal_plain_text_and_consumes_flow() =
        runTest(mainDispatcher) {
            val markdownBody = "**bold** _italic_ [x](http://evil.test) https://tracker.test/pixel"
            persistText(markdownBody, seq = 1)

            val vm = ChatFeedViewModel(graph())
            val first = collectUntil(vm) { it.isNotEmpty() }
            // The body is the RAW string — Markdown chars and the URL are literal, not parsed/linked.
            assertEquals(markdownBody, first.single().body)

            // new_message_from_poll_appears_in_open_feed: a later persist (as a background poll would do)
            // surfaces on its own via the Flow — no manual refresh, no second observable.
            persistText("arrived via poll", seq = 2)
            val updated = collectUntil(vm) { rows -> rows.any { it.body == "arrived via poll" } }
            assertTrue(updated.any { it.body == "arrived via poll" })
        }

    /** A message from another sender is not flagged as mine; my own is. */
    @Test
    fun feed_marks_own_messages() =
        runTest(mainDispatcher) {
            val other = AppTestSupport.newIdentity()
            persistText("mine", seq = 1, sender = identity)
            persistText("theirs", seq = 2, sender = other)

            val vm = ChatFeedViewModel(graph())
            val rows = collectUntil(vm) { it.size >= 2 }
            assertTrue(rows.first { it.body == "mine" }.isMine)
            assertFalse(rows.first { it.body == "theirs" }.isMine)
        }

    /**
     * Start an active collector so the WhileSubscribed StateFlow begins observing Room, advance the
     * scheduler, then return the first emission satisfying [predicate].
     */
    private suspend fun collectUntil(
        vm: ChatFeedViewModel,
        predicate: (List<ChatFeedViewModel.FeedRow>) -> Boolean,
    ): List<ChatFeedViewModel.FeedRow> =
        kotlinx.coroutines.coroutineScope {
            // Keep the WhileSubscribed StateFlow active; runTest auto-advances the scheduler while the
            // body below suspends on `first`, so the upstream Room Flow runs without an explicit advance.
            val collector = launch { vm.messages.collect { } }
            val result = vm.messages.first { predicate(it) }
            collector.cancel()
            result
        }
}
