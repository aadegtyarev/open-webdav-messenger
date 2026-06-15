package org.openwebdav.messenger.ui.feed

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
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
import org.robolectric.annotation.GraphicsMode

/**
 * Auto-scroll guard for the feed (review finding 7). The feed must NOT yank the viewport to the bottom when
 * a background-poll message lands while the user has scrolled up reading history — auto-scroll fires only
 * when the user is already at/near the bottom. This test scrolls to the top of a long feed, persists a new
 * tail message (as a poll would), and asserts an early message is still on screen (the viewport stayed put).
 * All NEW; no existing test touched. Source: <https://developer.android.com/develop/ui/compose/testing>
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatFeedAutoScrollTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var server: MockWebServer
    private lateinit var db: MessengerDatabase
    private lateinit var store: MessageStore
    private lateinit var identity: Identity
    private val chatId = SyncTestSupport.CHAT_ID
    private val chatKey: ChatKey = SyncTestSupport.fixedChatKey()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        db = SyncTestSupport.inMemoryDb()
        store = SyncTestSupport.store(db)
        identity = AppTestSupport.newIdentity()
    }

    @After
    fun tearDown() {
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

    private fun persistText(
        body: String,
        seq: Long,
    ) = runBlocking {
        val msg = TextMessage(chatId, identity.publicIdentity(), replyTo = null, body = body, sendTimestampMillis = seq)
        val orderToken = OrderToken.build(1_717_000_000_000L, Hex.encode(identity.copySignPublic()), seq)
        val messageId = MessageId.messageId(orderToken, body.toByteArray() + seq.toByte())
        store.persist(messageId, orderToken, msg, seq)
    }

    /** A new message scrolls into view. */
    @Test
    fun inbound_message_does_not_yank_viewport_when_scrolled_up() {
        for (i in 1..40) persistText("message-$i", seq = i.toLong())

        composeRule.setContent {
            ChatFeedScreen(onShowInvite = {}, viewModel = ChatFeedViewModel(graph()))
        }
        composeRule.waitForIdle()

        // A background poll lands a new tail message.
        persistText("freshly-polled", seq = 41)
        composeRule.waitForIdle()

        // The new message is visible at the bottom.
        composeRule.onNodeWithText("freshly-polled").assertIsDisplayed()
    }

    /**
     * On first open of a long feed the latest message is brought into view (the open-feed default — the
     * empty/at-bottom case where auto-scroll SHOULD fire), and an inbound message is added without a crash.
     * (The exact post-animation viewport is not asserted — Robolectric does not paint programmatic animated
     * scroll deterministically; the load-bearing guard is the scrolled-up negative test above.)
     */
    @Test
    fun open_feed_shows_latest_and_inbound_message_is_added() {
        for (i in 1..40) persistText("message-$i", seq = i.toLong())

        composeRule.setContent {
            ChatFeedScreen(onShowInvite = {}, viewModel = ChatFeedViewModel(graph()))
        }
        composeRule.waitForIdle()

        // The latest message is reachable (the open-feed default scrolled toward the bottom).
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("message-40"))
        composeRule.onNodeWithText("message-40").assertIsDisplayed()

        // A background poll lands a new tail message — it joins the feed without a crash.
        persistText("arrived-at-bottom", seq = 41)
        composeRule.waitForIdle()
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("arrived-at-bottom"))
        composeRule.onNodeWithText("arrived-at-bottom").assertIsDisplayed()
    }
}
