package org.openwebdav.messenger.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openwebdav.messenger.app.AppTestSupport
import org.openwebdav.messenger.app.EngineWiring
import org.openwebdav.messenger.app.RuntimeGraph
import org.openwebdav.messenger.crypto.Aead
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.crypto.MessageCrypto
import org.openwebdav.messenger.data.MessageStore
import org.openwebdav.messenger.data.MessengerDatabase
import org.openwebdav.messenger.identity.Identity
import org.openwebdav.messenger.keystore.StoredConnection
import org.openwebdav.messenger.message.MessageEnvelope
import org.openwebdav.messenger.protocol.Hex
import org.openwebdav.messenger.sync.CycleOutcome
import org.openwebdav.messenger.sync.FakeDisk
import org.openwebdav.messenger.sync.SyncEngine
import org.openwebdav.messenger.sync.SyncRunner
import org.openwebdav.messenger.sync.SyncTestSupport
import org.openwebdav.messenger.transport.ConnectionConfig
import org.openwebdav.messenger.transport.TransportFactory
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Start-destination routing for [AppRoot] (review finding 1). Process-start wiring runs asynchronously, so a
 * synchronous start-destination read used to race the warm-start and re-onboard a returning user. AppRoot
 * now collects [EngineWiring.ready] (via `AppContainer.ready`) and shows a loading state until the graph is
 * resolved, THEN routes: a persisted config → Feed (no re-onboarding), no config → Start. These tests drive
 * the readiness signal directly through the SAME `EngineWiring.initialize` path the production warm-start
 * uses, so the routing decision is asserted against the real signal, not a stub. All NEW; no existing test
 * touched. Source: <https://developer.android.com/develop/ui/compose/testing>
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppRootTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var server: MockWebServer
    private lateinit var db: MessengerDatabase
    private lateinit var identity: Identity
    private val chatId = "approot-chat-id-00000000001"
    private val chatKey: ChatKey = SyncTestSupport.fixedChatKey()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = FakeDisk()
        server.start()
        db = SyncTestSupport.inMemoryDb()
        identity = AppTestSupport.newIdentity()
        // Reset the process-global runner to the default no-op before each test.
        SyncRunner.install(SyncRunner { CycleOutcome(0, 0, backedOff = false) })
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    /** With no persisted config, once ready AppRoot routes to the Start fork (create vs join). */
    @Test
    fun no_config_routes_to_start_after_ready() {
        EngineWiring.initialize(JvmDeps(stored = null))
        composeRule.setContent { AppRoot() }
        composeRule.waitForIdle()

        // The first-launch fork is shown — both onboarding affordances are present.
        composeRule.onNodeWithContentDescription("Create a community — I host the disk").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Join by invite").assertIsDisplayed()
    }

    /**
     * With a persisted config, once ready AppRoot routes straight to the Feed — NOT back to the Start fork
     * (the race regression). The community name in the feed's top bar proves the joined graph drove routing.
     */
    @Test
    fun saved_config_routes_to_feed_not_start() {
        val stored = StoredConnection(SyncTestSupport.config(server), chatId, "My Community")
        EngineWiring.initialize(JvmDeps(stored = stored))
        composeRule.setContent { AppRoot() }
        composeRule.waitForIdle()

        // The feed opened (its top bar shows the community name); the Start fork is absent.
        composeRule.onNodeWithText("My Community").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Create a community — I host the disk").assertDoesNotExist()
    }

    /** A JVM [EngineWiring.Deps] backed by real libsodium + MockWebServer + in-memory Room (mirrors EngineWiringTest). */
    private inner class JvmDeps(
        private val stored: StoredConnection?,
    ) : EngineWiring.Deps {
        override fun loadStoredConnection(): StoredConnection? = stored

        override fun loadChatKey(chatId: String): ChatKey = chatKey

        override fun loadIdentity(): Identity = identity

        override fun buildGraph(
            config: ConnectionConfig,
            chatId: String,
            communityName: String,
            chatKey: ChatKey,
            identity: Identity,
        ): RuntimeGraph {
            val store = MessageStore(db.messageDao(), db.syncCursorDao())
            val envelope = MessageEnvelope.create(MessageCrypto(Aead(AppTestSupport.native())), AppTestSupport.identityCrypto())
            val engine =
                SyncEngine(
                    transport = TransportFactory.create(config),
                    envelope = envelope,
                    store = store,
                    keyProvider = { requested -> if (requested == chatId) chatKey else null },
                )
            return RuntimeGraph(
                engine = engine,
                store = store,
                envelope = envelope,
                config = config,
                chatId = chatId,
                communityName = communityName,
                chatKey = chatKey,
                identity = identity,
                senderIdentifier = Hex.encode(identity.copySignPublic()),
            )
        }

        override fun schedulePoll() = Unit
    }
}
