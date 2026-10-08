package org.openwebdav.messenger.ui.feed

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openwebdav.messenger.app.AppContainer
import org.openwebdav.messenger.app.AppTestSupport
import org.openwebdav.messenger.app.CachedVerifiedRoster
import org.openwebdav.messenger.app.EngineWiring
import org.openwebdav.messenger.app.RecipientReadiness
import org.openwebdav.messenger.app.RosterCacheProvenance
import org.openwebdav.messenger.app.RuntimeGraph
import org.openwebdav.messenger.app.VerifiedRosterCachePersistence
import org.openwebdav.messenger.crypto.Aead
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.crypto.MessageCrypto
import org.openwebdav.messenger.data.MessageStore
import org.openwebdav.messenger.data.MessengerDatabase
import org.openwebdav.messenger.directory.DirectoryEntry
import org.openwebdav.messenger.directory.DirectoryReadResult
import org.openwebdav.messenger.identity.Identity
import org.openwebdav.messenger.keystore.StoredConnection
import org.openwebdav.messenger.message.MessageEnvelope
import org.openwebdav.messenger.message.TextMessage
import org.openwebdav.messenger.protocol.Hex
import org.openwebdav.messenger.protocol.MessageId
import org.openwebdav.messenger.protocol.OrderToken
import org.openwebdav.messenger.sync.SyncEngine
import org.openwebdav.messenger.sync.SyncRunner
import org.openwebdav.messenger.sync.SyncTestSupport
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

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
        AppContainer.clearChatOpenTestSeam()
        server.shutdown()
        db.close()
    }

    private class MemoryRosterCache : VerifiedRosterCachePersistence {
        private val records = mutableMapOf<Pair<String, String>, CachedVerifiedRoster>()

        override fun load(
            communityId: String,
            chatId: String,
        ) = records[communityId to chatId]

        override fun put(entry: CachedVerifiedRoster) {
            records[entry.communityId to entry.chatId] = entry
        }

        override fun remove(
            communityId: String,
            chatId: String,
        ) {
            records.remove(communityId to chatId)
        }

        override fun clear() = records.clear()
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

    @Test
    fun app_container_group_and_dm_open_show_room_before_suspended_roster_during_poll() {
        val communityId = "default"
        val groupId = "group-open"
        val dmId = "dm-open"
        val stored = StoredConnection(SyncTestSupport.config(server), chatId, "Community")
        val chatKeys = mapOf(chatId to chatKey, groupId to chatKey, dmId to chatKey)
        val pollStarted = CompletableDeferred<Unit>()
        val releasePoll = CompletableDeferred<ByteArray?>()
        EngineWiring.initialize(
            AppTestSupport.chatOpenTestDeps(
                server,
                db,
                communityId,
                stored,
                identity,
                chatKeys,
                rawFileRead = {
                    pollStarted.complete(Unit)
                    releasePoll.await()
                },
            ),
        )
        val reads = LinkedBlockingQueue<CompletableDeferred<DirectoryReadResult>>()
        val enrichmentCompletions = LinkedBlockingQueue<CompletableDeferred<Unit>>()
        AppContainer.configureChatOpenTestSeam(
            communityId,
            AppContainer.ChatOpenTestSeam(
                loadChatKey = chatKeys::get,
                loadStored = { stored },
                readDirectory = { CompletableDeferred<DirectoryReadResult>().also(reads::put).await() },
                onRosterEnrichmentCompleted = { enrichmentCompletions.poll()?.complete(Unit) },
            ),
        )
        seedHistory(groupId, "group history")
        seedHistory(dmId, "dm history")
        val visibleGraph = mutableStateOf<RuntimeGraph?>(null)
        composeRule.setContent {
            visibleGraph.value?.let { ChatFeedScreen(onShowInvite = {}, viewModel = ChatFeedViewModel(it)) }
        }
        val poll = CoroutineScope(Dispatchers.IO).launch { SyncRunner.current().runOnce() }
        try {
            runBlocking { withTimeout(5_000) { pollStarted.await() } }
            val groupGraph = openInstalledChat(groupId, "Group")
            visibleGraph.value = groupGraph
            composeRule.waitUntil(5_000) {
                runCatching {
                    composeRule.onNodeWithText("group history").assertIsDisplayed()
                    true
                }.getOrDefault(false)
            }
            val oldRead = reads.poll(5, TimeUnit.SECONDS) ?: error("group roster read did not start")
            assertEquals(RecipientReadiness.Loading, groupGraph.recipientSnapshot())
            composeRule.onNodeWithContentDescription("Reading participants from server").assertIsDisplayed()
            composeRule.onNodeWithContentDescription("Message").assertIsNotEnabled()
            composeRule.onNodeWithContentDescription("Send").assertIsNotEnabled()

            val failedB = AppContainer.beginChatOpenRequest()
            assertFalse(runBlocking { AppContainer.openGroupChat("missing-key", "Missing", communityId, requestToken = failedB) })
            val oldReadFinished = CompletableDeferred<Unit>()
            enrichmentCompletions.put(oldReadFinished)
            oldRead.complete(DirectoryReadResult(listOf(verifiedPeer()), 0))
            runBlocking { withTimeout(5_000) { oldReadFinished.await() } }
            composeRule.waitForIdle()
            assertTrue(groupGraph.recipientSnapshot() is RecipientReadiness.Unavailable)
            composeRule.onNodeWithText("Verified members unavailable — reconnect and retry").assertIsDisplayed()
            composeRule.onNodeWithContentDescription("Send").assertIsNotEnabled()
            composeRule.onNodeWithText("Retry").performClick()
            val retryRead = reads.poll(5, TimeUnit.SECONDS) ?: error("retry roster read did not start")
            val retryReadFinished = CompletableDeferred<Unit>()
            enrichmentCompletions.put(retryReadFinished)
            retryRead.complete(DirectoryReadResult(listOf(verifiedPeer()), 0))
            runBlocking { withTimeout(5_000) { retryReadFinished.await() } }
            composeRule.waitUntil(5_000) { groupGraph.recipientSnapshot() is RecipientReadiness.Ready }
            composeRule.onNodeWithContentDescription("Message").performTextInput("hello")
            composeRule.onNodeWithContentDescription("Send").assertIsEnabled()

            val dmGraph = openInstalledChat(dmId, "DM")
            visibleGraph.value = dmGraph
            composeRule.waitUntil(5_000) {
                runCatching {
                    composeRule.onNodeWithText("dm history").assertIsDisplayed()
                    true
                }.getOrDefault(false)
            }
            val dmRead = reads.poll(5, TimeUnit.SECONDS) ?: error("DM roster read did not start")
            composeRule.onNodeWithContentDescription("Reading participants from server").assertIsDisplayed()
            composeRule.onNodeWithContentDescription("Message").assertIsNotEnabled()
            composeRule.onNodeWithContentDescription("Send").assertIsNotEnabled()
            val dmReadFinished = CompletableDeferred<Unit>()
            enrichmentCompletions.put(dmReadFinished)
            dmRead.complete(DirectoryReadResult(listOf(verifiedPeer()), 0))
            runBlocking { withTimeout(5_000) { dmReadFinished.await() } }
            composeRule.waitUntil(5_000) { dmGraph.recipientSnapshot() is RecipientReadiness.Ready }
            composeRule.onNodeWithContentDescription("Message").performTextInput("dm send")
            composeRule.onNodeWithContentDescription("Send").assertIsEnabled()
        } finally {
            releasePoll.complete(null)
            runBlocking { withTimeout(5_000) { poll.cancelAndJoin() } }
            AppContainer.clearChatOpenTestSeam()
            EngineWiring.initialize(AppTestSupport.emptyEngineDeps())
        }
    }

    private fun openInstalledChat(
        chatId: String,
        name: String,
    ): RuntimeGraph {
        val request = AppContainer.beginChatOpenRequest()
        assertTrue(
            runBlocking {
                withTimeout(3_000) { AppContainer.openGroupChat(chatId, name, "default", requestToken = request) }
            },
        )
        return checkNotNull(AppContainer.runtimeGraph())
    }

    private fun seedHistory(
        chatId: String,
        body: String,
    ) = runBlocking {
        val message = TextMessage(chatId, identity.publicIdentity(), null, body, 4L)
        store.persist("history-$chatId", "0004", message, 4L)
    }

    private fun verifiedPeer() = DirectoryEntry("Peer", ByteArray(32) { 1 }, ByteArray(32) { 2 })

    @Test
    fun cached_general_is_ready_before_suspended_reader() {
        val communityId = "default"
        val stored = StoredConnection(SyncTestSupport.config(server), chatId, "Community")
        EngineWiring.initialize(
            AppTestSupport.chatOpenTestDeps(server, db, communityId, stored, identity, mapOf(chatId to chatKey)),
        )
        val persistence = MemoryRosterCache()
        val read = CompletableDeferred<DirectoryReadResult>()
        val completed = CompletableDeferred<Unit>()
        val provenance = RosterCacheProvenance.digest(communityId, chatId, chatKey, chatKey, identity)
        persistence.put(CachedVerifiedRoster(communityId, chatId, provenance, listOf(verifiedPeer())))
        AppContainer.configureChatOpenTestSeam(
            communityId,
            AppContainer.ChatOpenTestSeam(
                loadChatKey = { chatKey },
                loadStored = { stored },
                readDirectory = { read.await() },
                cachePersistence = persistence,
                onGeneralRosterCompleted = { completed.complete(Unit) },
            ),
        )
        val graph = checkNotNull(AppContainer.runtimeGraph())
        AppContainer.startIndependentGeneralRosterRefreshForStartupOrRestore(graph)
        assertTrue(graph.recipientSnapshot() is RecipientReadiness.Ready)
        assertEquals("Peer", graph.memberNames.values.single())
        read.complete(DirectoryReadResult(emptyList(), 0, listingFailed = true))
        runBlocking { withTimeout(5_000) { completed.await() } }
        assertTrue(graph.recipientSnapshot() is RecipientReadiness.Ready)
    }

    @Test
    fun cached_group_and_dm_open_ready_before_suspended_reader() {
        val communityId = "default"
        val stored = StoredConnection(SyncTestSupport.config(server), chatId, "Community")
        val groupId = "cached-group"
        val dmId = "cached-dm"
        val keys = mapOf(chatId to chatKey, groupId to chatKey, dmId to chatKey)
        EngineWiring.initialize(AppTestSupport.chatOpenTestDeps(server, db, communityId, stored, identity, keys))
        val persistence = MemoryRosterCache()
        val pendingReads = LinkedBlockingQueue<CompletableDeferred<DirectoryReadResult>>()
        val completions = LinkedBlockingQueue<CompletableDeferred<Unit>>()
        AppContainer.configureChatOpenTestSeam(
            communityId,
            AppContainer.ChatOpenTestSeam(
                loadChatKey = keys::get,
                loadStored = { stored },
                readDirectory = { CompletableDeferred<DirectoryReadResult>().also(pendingReads::put).await() },
                onRosterEnrichmentCompleted = { completions.poll()?.complete(Unit) },
                cachePersistence = persistence,
                chatKind = { _, id -> if (id == dmId) "dm" else "group" },
            ),
        )
        listOf(groupId to "group", dmId to "dm").forEach { (id, kind) ->
            val provenance = RosterCacheProvenance.digest(communityId, id, chatKey, chatKey, identity)
            persistence.put(CachedVerifiedRoster(communityId, id, provenance, listOf(verifiedPeer())))
            val completed = CompletableDeferred<Unit>()
            completions.put(completed)
            val request = AppContainer.beginChatOpenRequest()
            assertTrue(runBlocking { AppContainer.openGroupChat(id, kind, communityId, requestToken = request) })
            val installed = checkNotNull(AppContainer.runtimeGraph())
            assertTrue(installed.recipientSnapshot() is RecipientReadiness.Ready)
            pendingReads.poll(5, TimeUnit.SECONDS)!!.complete(DirectoryReadResult(emptyList(), 0, listingFailed = true))
            runBlocking { withTimeout(5_000) { completed.await() } }
        }
    }

    @Test
    fun cached_ready_roster_has_no_loading_surface_and_keeps_send_enabled() {
        val graph = graph().apply { updateRecipientReadiness(RecipientReadiness.Ready(listOf(senderIdentifier, "peer"))) }
        composeRule.setContent {
            ChatFeedScreen(onShowInvite = {}, viewModel = ChatFeedViewModel(graph))
        }
        composeRule.onNodeWithContentDescription("Reading participants from server").assertDoesNotExist()
        composeRule.onNodeWithText("Message").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Message").performTextInput("hello")
        composeRule.onNodeWithContentDescription("Send").assertIsEnabled()
    }

    @Test
    fun group_send_waits_for_verified_roster_and_surfaces_retry_state_accessibly() {
        val graph = graph().apply { updateRecipientReadiness(RecipientReadiness.Loading) }
        composeRule.setContent {
            ChatFeedScreen(onShowInvite = {}, viewModel = ChatFeedViewModel(graph))
        }
        composeRule.onNodeWithContentDescription("Reading participants from server")
            .assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        composeRule.onNodeWithText("Reading participants from server…").assertIsDisplayed()
        composeRule.onNodeWithText("Reading participants from server. Sending will be available when complete.").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Message").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Send").assertIsNotEnabled()

        graph.updateRecipientReadiness(
            RecipientReadiness.Ready(listOf(graph.senderIdentifier, "peer")),
        )
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Message").performTextInput("hello")
        composeRule.onNodeWithContentDescription("Send").assertIsEnabled()

        graph.updateRecipientReadiness(
            RecipientReadiness.Unavailable("Verified members unavailable — reconnect and retry"),
        )
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Verified members unavailable — reconnect and retry").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").assertIsDisplayed()
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

    @Test
    fun failed_message_retry_is_a_labelled_accessible_target() {
        runBlocking {
            val body = "retry me"
            val message = TextMessage(chatId, identity.publicIdentity(), replyTo = null, body = body, sendTimestampMillis = 3)
            val orderToken = OrderToken.build(1_717_000_000_000L, Hex.encode(identity.copySignPublic()), 3)
            val messageId = MessageId.messageId(orderToken, body.toByteArray())
            store.persist(messageId, orderToken, message, 3, sendStatus = org.openwebdav.messenger.data.MessageEntity.STATUS_FAILED)
        }
        composeRule.setContent {
            ChatFeedScreen(onShowInvite = {}, viewModel = ChatFeedViewModel(graph()))
        }
        composeRule.onNodeWithContentDescription("Retry failed message")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
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
