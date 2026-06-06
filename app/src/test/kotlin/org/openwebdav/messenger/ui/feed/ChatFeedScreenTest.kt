package org.openwebdav.messenger.ui.feed

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
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
 * Compose `createComposeRule` UI test for the feed + composer (`ui-chat-surface` plan Test plan; Scenarios
 * 5–6). Persisted history renders as **literal plain text** rows (a Markdown/URL body shows verbatim — SC8
 * stays closed), an empty chat shows the empty-state prompt, and the Send action gates on a non-blank draft.
 * Built from a real [RuntimeGraph] over an in-memory Room DB (the same substrate the production feed
 * observes). UI logic only — send/seal/write is in the ViewModel/MessageSendService off the UI thread.
 * Source: <https://developer.android.com/develop/ui/compose/testing>
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatFeedScreenTest {
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

    /** An empty chat shows the empty-state prompt and the disabled-until-typed Send action. */
    @Test
    fun empty_feed_shows_prompt_and_disabled_send() {
        composeRule.setContent {
            ChatFeedScreen(onShowInvite = {}, viewModel = ChatFeedViewModel(graph()))
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("No messages yet — say hello.").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Send").assertIsNotEnabled()
    }

    /** A body with Markdown syntax + a URL renders literally as a feed row (SC8 stays closed). */
    @Test
    fun feed_renders_message_as_literal_plain_text() {
        val body = "**bold** [x](http://evil.test) https://tracker.test/pixel"
        persistText(body, seq = 1)

        composeRule.setContent {
            ChatFeedScreen(onShowInvite = {}, viewModel = ChatFeedViewModel(graph()))
        }
        composeRule.waitForIdle()

        // The verbatim string is on screen — no markdown styling, no tappable link rewrites the text.
        composeRule.onNodeWithText(body).assertIsDisplayed()
    }

    /** The Send action enables once the composer draft is non-blank. */
    @Test
    fun send_enables_when_draft_non_blank() {
        composeRule.setContent {
            ChatFeedScreen(onShowInvite = {}, viewModel = ChatFeedViewModel(graph()))
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Send").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Message").performTextInput("hello")
        composeRule.onNodeWithContentDescription("Send").assertIsEnabled()
    }
}
