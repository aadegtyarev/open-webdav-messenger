package org.openwebdav.messenger.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openwebdav.messenger.app.AppContainer
import org.openwebdav.messenger.app.AppTestSupport
import org.openwebdav.messenger.app.EngineWiring
import org.openwebdav.messenger.app.RecipientReadiness
import org.openwebdav.messenger.app.RuntimeGraph
import org.openwebdav.messenger.crypto.Aead
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.crypto.MessageCrypto
import org.openwebdav.messenger.data.MessageStore
import org.openwebdav.messenger.data.MessengerDatabase
import org.openwebdav.messenger.directory.DirectoryEntry
import org.openwebdav.messenger.directory.DirectoryReadResult
import org.openwebdav.messenger.identity.Identity
import org.openwebdav.messenger.identity.IdentityCrypto
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
import org.robolectric.RuntimeEnvironment
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
    val composeRule = createAndroidComposeRule<ComponentActivity>()

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
        AppContainer.clearChatOpenTestSeam()
        EngineWiring.initialize(JvmDeps(stored = null))
        server.shutdown()
        db.close()
    }

    @Test
    fun participants_destination_is_saveable_and_back_returns_to_feed() {
        assertEquals("participants", Screen.Participants.persistedRoute())
        assertEquals(Screen.Participants, screenForSavedRoute("participants"))
        assertEquals(Screen.Feed, Screen.Participants.systemBackDestination(hasCommunities = true))
    }

    @Test
    fun app_nav_participants_route_restores_and_returns_by_toolbar_and_system_back() {
        val stored = StoredConnection(SyncTestSupport.config(server), chatId, "My Community")
        EngineWiring.initialize(JvmDeps(stored = stored))
        // This restores AppNav's production rememberSaveable state; the Robolectric Compose host cannot re-install content after ActivityScenario.recreate().
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent { AppNav(initialScreen = Screen.Feed) }
        composeRule.onNodeWithContentDescription("Participants").performClick()
        composeRule.onNodeWithText("Participants").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithText("Participants").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Back to chat").performClick()
        composeRule.onNodeWithContentDescription("Message").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Participants").performClick()
        composeRule.activity.onBackPressedDispatcher.onBackPressed()
        composeRule.onNodeWithContentDescription("Message").assertIsDisplayed()
    }

    @Test
    fun app_nav_returns_to_feed_when_the_exact_runtime_graph_changes() {
        val first = StoredConnection(SyncTestSupport.config(server), chatId, "My Community")
        EngineWiring.initialize(JvmDeps(stored = first))
        composeRule.setContent { AppNav(initialScreen = Screen.Feed) }
        composeRule.onNodeWithContentDescription("Participants").performClick()
        composeRule.onNodeWithText("Participants").assertIsDisplayed()

        val switched = first.copy(chatId = "other-chat-id-00000000001")
        composeRule.runOnIdle { EngineWiring.initialize(JvmDeps(stored = switched)) }
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithContentDescription("Message").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("Participants").assertDoesNotExist()
    }

    @Test
    fun participants_retry_button_runs_the_guarded_roster_refresh() {
        AppContainer.bind(RuntimeEnvironment.getApplication())
        val stored = StoredConnection(SyncTestSupport.config(server), chatId, "My Community")
        EngineWiring.initialize(JvmDeps(stored = stored))
        val graph = checkNotNull(AppContainer.runtimeGraph())
        graph.updateRecipientReadiness(RecipientReadiness.Unavailable("offline"))
        val readStarted = CompletableDeferred<Unit>()
        val readResult = CompletableDeferred<DirectoryReadResult>()
        val readFinished = CompletableDeferred<Unit>()
        AppContainer.configureChatOpenTestSeam(
            "default",
            AppContainer.ChatOpenTestSeam(
                loadChatKey = { chatKey },
                loadStored = { stored },
                readDirectory = {
                    readStarted.complete(Unit)
                    readResult.await()
                },
                onRosterEnrichmentCompleted = { readFinished.complete(Unit) },
            ),
        )
        composeRule.setContent { AppNav(initialScreen = Screen.Feed) }
        composeRule.onNodeWithContentDescription("Participants").performClick()
        composeRule.onNodeWithText("Retry").performClick()
        runBlocking { withTimeout(5_000) { readStarted.await() } }
        readResult.complete(DirectoryReadResult(listOf(DirectoryEntry("Retry peer", ByteArray(32) { 19 }, ByteArray(32) { 20 })), 0))
        runBlocking { withTimeout(5_000) { readFinished.await() } }
        composeRule.onNodeWithText("Retry peer").assertIsDisplayed()
        assertTrue((graph.recipientSnapshot() as RecipientReadiness.Ready).participants.any { it.displayName == "Retry peer" })
    }

    @Test
    fun successful_restore_activity_result_returns_to_chats_and_invalidates_settings() {
        val update = accountRestoreNavigationResult(Screen.Settings, 4, android.app.Activity.RESULT_OK)
        assertEquals(Screen.CommunityList, update.screen)
        assertEquals(5, update.revision)
        val cancelled = accountRestoreNavigationResult(Screen.Settings, 4, android.app.Activity.RESULT_CANCELED)
        assertEquals(Screen.Settings, cancelled.screen)
        assertEquals(4, cancelled.revision)
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
     * With a persisted config, once ready AppRoot loads the runtime but opens the Chats list rather than
     * forcing a chat feed.
     */
    @Test
    fun saved_config_routes_to_chats_without_opening_a_feed() {
        val stored = StoredConnection(SyncTestSupport.config(server), chatId, "My Community")
        EngineWiring.initialize(JvmDeps(stored = stored))
        composeRule.setContent { AppRoot() }
        composeRule.waitForIdle()

        // Runtime was resolved and the neutral Chats list is shown; neither feed nor onboarding is opened.
        composeRule.onNodeWithText("Chats").assertIsDisplayed()
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
            communityId: String,
        ): RuntimeGraph {
            val store = MessageStore(db.messageDao(), db.syncCursorDao(), communityId)
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
                communityId = communityId,
            )
        }

        override fun schedulePoll(communityMinPollSeconds: Int?) = Unit

        override fun communityChatIds(communityId: String): List<String> = listOf(chatId)

        override fun identityCrypto(): IdentityCrypto = AppTestSupport.identityCrypto()

        override suspend fun readRawFile(
            config: ConnectionConfig,
            path: String,
        ): ByteArray? = null

        override fun saveRotatedConfig(
            newConfig: ConnectionConfig,
            communityId: String,
        ): Boolean = false
    }
}
