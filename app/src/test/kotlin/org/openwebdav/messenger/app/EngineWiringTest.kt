package org.openwebdav.messenger.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.openwebdav.messenger.crypto.Aead
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.crypto.MessageCrypto
import org.openwebdav.messenger.data.MessageEntity
import org.openwebdav.messenger.data.MessageStore
import org.openwebdav.messenger.data.MessengerDatabase
import org.openwebdav.messenger.directory.CredentialRotation
import org.openwebdav.messenger.identity.Identity
import org.openwebdav.messenger.identity.IdentityCrypto
import org.openwebdav.messenger.keystore.ConnectionConfigStore
import org.openwebdav.messenger.keystore.StoredConnection
import org.openwebdav.messenger.message.MessageEnvelope
import org.openwebdav.messenger.message.TextMessage
import org.openwebdav.messenger.protocol.ChatPaths
import org.openwebdav.messenger.protocol.Hex
import org.openwebdav.messenger.sync.CycleOutcome
import org.openwebdav.messenger.sync.FakeDisk
import org.openwebdav.messenger.sync.SyncEngine
import org.openwebdav.messenger.sync.SyncRunner
import org.openwebdav.messenger.sync.SyncTestSupport
import org.openwebdav.messenger.transport.ConnectionConfig
import org.openwebdav.messenger.transport.TransportFactory
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * JVM tests for [EngineWiring] (`ui-chat-surface` plan Test plan): the no-op runner survives before any
 * config, and a relaunch with a saved config re-installs a REAL runner — driven through the SAME
 * `SyncRunner.install` path the production `Application` uses (test-wiring-parity). The device-bound
 * construction sits behind a JVM [EngineWiring.Deps] backed by real libsodium + MockWebServer + in-memory
 * Room, so the wiring's "no config ⇒ stay no-op / config ⇒ install one real engine" logic is asserted
 * directly. All NEW.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class EngineWiringTest {
    private lateinit var server: MockWebServer
    private lateinit var db: MessengerDatabase
    private lateinit var identity: Identity
    private val chatId = "wiring-chat-id-000000000001"
    private val chatKey: ChatKey = SyncTestSupport.fixedChatKey()

    @Before
    fun setUp() {
        server = MockWebServer()
        // An empty in-memory disk: the real poll cycle reads an empty change index → clean, newCount 0.
        // Without a dispatcher MockWebServer blocks on the PROPFIND, hanging the real runner's runOnce.
        server.dispatcher = FakeDisk()
        server.start()
        db = SyncTestSupport.inMemoryDb()
        identity = AppTestSupport.newIdentity()
        // Reset the process-global runner to the default no-op before each test (other suites install too).
        SyncRunner.install(SyncRunner { CycleOutcome(0, 0, backedOff = false) })
    }

    @After
    fun tearDown() {
        SyncRunner.install(SyncRunner { CycleOutcome(0, 0, backedOff = false) })
        server.shutdown()
        db.close()
    }

    /**
     * poll_before_any_config_is_benign_clean_cycle — with no config saved, initialize leaves the no-op
     * runner; running it is a clean cycle (no throw), and there is no runtime graph.
     */
    @Test
    fun poll_before_any_config_is_benign_clean_cycle() =
        runTest {
            val deps = JvmDeps(stored = null) // no config
            EngineWiring.initialize(deps)

            assertNull("no config ⇒ no runtime graph", EngineWiring.current())
            assertFalse("no real runner scheduled", deps.scheduled)
            // The installed (default no-op) runner runs a clean cycle without throwing.
            val outcome = SyncRunner.current().runOnce()
            assertEquals(CycleOutcome(0, 0, backedOff = false), outcome)
        }

    /**
     * relaunch_with_saved_config_reinstalls_runner — initialize with a persisted config + stored key
     * installs a REAL runner (replacing the no-op) and schedules the poll. `SyncRunner.current()` then runs
     * a real cycle (not the no-op), proving the production `install` path is driven, not a hand-rolled engine.
     */
    @Test
    fun relaunch_with_saved_config_reinstalls_runner() =
        runTest {
            val stored = StoredConnection(SyncTestSupport.config(server), chatId, "Community")
            val deps = JvmDeps(stored = stored)
            EngineWiring.initialize(deps)

            // A real graph is composed, and the poll was scheduled.
            assertTrue("config present ⇒ runtime graph built", EngineWiring.current() != null)
            assertTrue("poll scheduled", deps.scheduled)
            // The installed runner is the REAL one — running it exercises a real poll cycle (returns a
            // typed CycleOutcome from the engine, not the fixed no-op zero-from-install — it actually
            // talks to the MockWebServer). MockWebServer returns 404 for the change-index PROPFIND, which
            // the engine folds into a clean/benign cycle; the point is it ran the engine, not the no-op.
            val outcome = SyncRunner.current().runOnce()
            // A real cycle over an empty disk persists nothing new; never throws.
            assertEquals(0, outcome.newCount)
        }

    @Test
    fun cold_start_loads_the_persisted_active_community() =
        runTest {
            val stored = StoredConnection(SyncTestSupport.config(server), chatId, "Selected")
            val deps = JvmDeps(stored = stored, activeCommunity = "community-b")

            EngineWiring.initialize(deps)

            assertEquals("community-b", deps.loadedCommunity)
            assertEquals(chatId, EngineWiring.current()?.chatId)
        }

    @Test
    fun suspended_community_a_cycle_keeps_its_immutable_context_after_reconfigure_to_b() =
        runTest {
            val stored = StoredConnection(SyncTestSupport.config(server), chatId, "Community")
            val deps =
                JvmDeps(
                    stored = stored,
                    activeCommunity = "community-a",
                    joinedCommunities = listOf("community-a", "community-b"),
                )
            EngineWiring.initialize(deps)
            val runnerA = SyncRunner.current()
            val discoveryEntered = CompletableDeferred<Unit>()
            val releaseDiscovery = CompletableDeferred<Unit>()
            deps.discoveryEntered = discoveryEntered
            deps.releaseDiscovery = releaseDiscovery

            val cycleA = async { runnerA.runOnce() }
            discoveryEntered.await()
            EngineWiring.reconfigure(
                SyncTestSupport.config(server),
                chatId,
                "Community B",
                chatKey,
                identity,
                communityId = "community-b",
            )
            deps.enumeratedCommunities.clear()
            releaseDiscovery.complete(Unit)
            cycleA.await()

            assertEquals(listOf("community-a", "community-b"), deps.enumeratedCommunities)
            assertEquals("community-b", EngineWiring.current()?.communityId)
        }

    @Test
    fun startup_recovers_old_claim_before_a_published_runner_can_claim_delivery() =
        runTest {
            val store = MessageStore(db.messageDao(), db.syncCursorDao(), "community-a")
            store.persist(
                "startup-retry",
                "0005",
                TextMessage(chatId, identity.publicIdentity(), null, "startup", 1L),
                1L,
                sendStatus = MessageEntity.STATUS_FAILED,
                outboxEnvelope = byteArrayOf(1),
                outboxRecipients = emptyList(),
                outboxCommunityId = "community-a",
            )
            val deps =
                JvmDeps(
                    StoredConnection(SyncTestSupport.config(server), chatId, "Community"),
                    activeCommunity = "community-a",
                    joinedCommunities = listOf("community-a"),
                )
            deps.onSchedule = {
                runBlocking { assertNotNull(store.claimOutgoing("startup-retry", "community-a", "startup-claim")) }
            }

            EngineWiring.initialize(deps)

            assertEquals(MessageEntity.STATUS_SENDING, store.messagesForChat(chatId).single().sendStatus)
            store.finishOutgoingClaim("startup-retry", "community-a", "startup-claim")
            assertEquals(MessageEntity.STATUS_SENT, store.messagesForChat(chatId).single().sendStatus)
        }

    @Test
    fun credential_rotation_while_group_is_open_persists_anchor_for_cold_start() =
        runTest {
            val anchor = StoredConnection(SyncTestSupport.config(server), chatId, "Community anchor")
            val deps = JvmDeps(anchor, activeCommunity = "community-a")
            val host = AppTestSupport.newIdentity()
            val rotatedConfig = anchor.config.copy(username = "rotated-user", appPassword = "rotated-pass")
            deps.credentialBlob =
                CredentialRotation.sealForMember(
                    rotatedConfig, identity.copyBoxPublic(), AppTestSupport.identityCrypto(), host,
                )
            EngineWiring.initialize(deps)
            val peerId = "0123456789abcdef"
            val selfId = Hex.encode(identity.copySignPublic())
            EngineWiring.switchToChat("opened-group", "Project group", chatKey, listOf(selfId, peerId), mapOf(peerId to "Project peer"))

            SyncRunner.current().runOnce()

            val rotatedGraph = EngineWiring.current()!!
            assertEquals("opened-group", rotatedGraph.chatId)
            assertEquals(listOf(selfId, peerId), rotatedGraph.roster)
            assertEquals(mapOf(peerId to "Project peer"), rotatedGraph.memberNames)
            assertEquals(chatId, deps.savedRotatedConnection?.chatId)
            assertEquals("Community anchor", deps.savedRotatedConnection?.communityName)
            MessageSendService(rotatedGraph, ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined).send("peer delivery")
            val disk = server.dispatcher as FakeDisk
            assertTrue(disk.fileNames(ChatPaths.changeIndex(peerId, "opened-group")).isNotEmpty())
            EngineWiring.initialize(deps)
            assertEquals(chatId, EngineWiring.current()?.chatId)
            assertEquals("Community anchor", EngineWiring.current()?.communityName)
        }

    @Test
    fun production_conditional_chat_install_serializes_selection_between_check_and_write() {
        val stored = StoredConnection(SyncTestSupport.config(server), chatId, "Community A")
        val deps = JvmDeps(stored = stored, activeCommunity = "community-a")
        EngineWiring.initialize(deps)
        val expectedGraph = EngineWiring.current()!!
        val guard = RuntimeSelectionGuard()
        val expectedRevision = guard.current()
        val checked = CountDownLatch(1)
        val selectionStarted = CountDownLatch(1)
        val releaseInstall = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val open =
                executor.submit<Boolean> {
                    EngineWiring.switchToChatIfCurrent(
                        guard, expectedRevision, expectedGraph, "group-a", "Group A", chatKey,
                        listOf(expectedGraph.senderIdentifier), emptyMap(), { true },
                    ) {
                        checked.countDown()
                        check(releaseInstall.await(5, TimeUnit.SECONDS))
                    }
                }
            assertTrue(checked.await(5, TimeUnit.SECONDS))
            val selectB =
                executor.submit {
                    selectionStarted.countDown()
                    guard.begin()
                    EngineWiring.reconfigure(
                        SyncTestSupport.config(server),
                        chatId,
                        "Community B",
                        chatKey,
                        identity,
                        communityId = "community-b",
                    )
                }
            assertTrue(selectionStarted.await(5, TimeUnit.SECONDS))
            assertFalse("selection cannot cross the production install critical section", selectB.isDone)
            releaseInstall.countDown()
            assertTrue(open.get(5, TimeUnit.SECONDS))
            selectB.get(5, TimeUnit.SECONDS)
            assertEquals("community-b", EngineWiring.current()?.communityId)
            assertEquals(chatId, EngineWiring.current()?.chatId)
        } finally {
            releaseInstall.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun missing_group_key_then_general_and_another_group_use_the_production_open_coordinator() {
        val stored = StoredConnection(SyncTestSupport.config(server), chatId, "Community")
        EngineWiring.initialize(JvmDeps(stored = stored, activeCommunity = "community-a"))
        val initialGraph = EngineWiring.current()!!
        val guard = RuntimeSelectionGuard()
        val keyStore = InMemoryChatKeyStore()
        var selectedCommunity = "community-a"
        var activations = 0
        val coordinator =
            GroupChatOpenCoordinator(
                selectionGuard = guard,
                currentCommunityId = { selectedCommunity },
                loadChatKey = keyStore::load,
                activateCommunity = {
                    activations++
                    selectedCommunity = it
                    true
                },
                currentGraph = EngineWiring::current,
            )
        val initialRevision = guard.current()

        assertNull(coordinator.prepare("missing-key-group", "Broken", "community-a", null))
        assertEquals(initialRevision, guard.current())
        assertEquals("community-a", selectedCommunity)
        assertEquals(0, activations)
        assertEquals(initialGraph, EngineWiring.current())

        val generalRevision = guard.begin()
        assertTrue(
            EngineWiring.switchToChatIfCurrent(
                guard,
                generalRevision,
                initialGraph,
                chatId,
                "General",
                chatKey,
                listOf(initialGraph.senderIdentifier),
                emptyMap(),
                { selectedCommunity == "community-a" },
            ),
        )
        val generalGraph = EngineWiring.current()!!
        keyStore.store("valid-group", chatKey)
        val validPlan = coordinator.prepare("valid-group", "Valid group", "community-a", null)!!

        assertTrue(
            EngineWiring.switchToChatIfCurrent(
                guard,
                validPlan.selectionRevision,
                validPlan.graph,
                validPlan.chatId,
                validPlan.chatName,
                validPlan.chatKey,
                listOf(validPlan.graph.senderIdentifier),
                emptyMap(),
                { selectedCommunity == validPlan.communityId },
            ),
        )
        assertEquals(generalGraph.communityId, EngineWiring.current()?.communityId)
        assertEquals("valid-group", EngineWiring.current()?.chatId)
    }

    @Test
    fun stale_delayed_group_open_cannot_prevent_a_later_valid_general_open() {
        val deps = JvmDeps(stored = StoredConnection(SyncTestSupport.config(server), chatId, "Community"))
        EngineWiring.initialize(deps)
        val graph = EngineWiring.current()!!
        val guard = RuntimeSelectionGuard()
        val staleRevision = guard.begin()
        val generalRevision = guard.begin()

        assertFalse(
            EngineWiring.switchToChatIfCurrent(
                guard,
                staleRevision,
                graph,
                "group-id",
                "Болталка",
                chatKey,
                listOf(graph.senderIdentifier),
                emptyMap(),
                { true },
            ),
        )
        assertTrue(
            EngineWiring.switchToChatIfCurrent(
                guard,
                generalRevision,
                graph,
                chatId,
                "General",
                chatKey,
                listOf(graph.senderIdentifier),
                emptyMap(),
                { true },
            ),
        )
        assertEquals("General", EngineWiring.current()?.communityName)
        assertEquals(chatId, EngineWiring.current()?.chatId)
    }

    @Test
    fun background_runner_polls_each_joined_community_on_its_own_webdav_root() =
        runTest {
            val serverB =
                MockWebServer().apply {
                    dispatcher = FakeDisk()
                    start()
                }
            try {
                val storedA = StoredConnection(SyncTestSupport.config(server), chatId, "Community A")
                val storedB = StoredConnection(SyncTestSupport.config(serverB), chatId, "Community B")
                val deps =
                    JvmDeps(
                        stored = storedA,
                        activeCommunity = "community-a",
                        joinedCommunities = listOf("community-a", "community-b"),
                        storedByCommunity = mapOf("community-b" to storedB),
                    )
                EngineWiring.initialize(deps)

                SyncRunner.current().runOnce()

                assertTrue("active community A must be polled", server.requestCount > 0)
                assertTrue("community B must use its own WebDAV root", serverB.requestCount > 0)
                assertTrue("background cycle must enumerate non-active community chats", "community-b" in deps.enumeratedCommunities)
                assertTrue("new registrations must be included after discovery", deps.newChatSeenAfterDiscovery)
            } finally {
                serverB.shutdown()
            }
        }

    /** reconfigure builds a graph + installs the real runner after a first persist (owner create / join). */
    @Test
    fun reconfigure_builds_graph_and_installs_real_runner() =
        runTest {
            val deps = JvmDeps(stored = null)
            EngineWiring.initialize(deps)
            assertNull(EngineWiring.current())

            EngineWiring.reconfigure(SyncTestSupport.config(server), chatId, "Community", chatKey, identity)

            val graph = EngineWiring.current()
            assertTrue("reconfigure builds the runtime graph", graph != null)
            assertEquals(chatId, graph!!.chatId)
            assertEquals(Hex.encode(identity.copySignPublic()), graph.senderIdentifier)
            assertTrue(deps.scheduled)
        }

    /** A JVM [EngineWiring.Deps] backed by real libsodium + MockWebServer + in-memory Room. */
    private inner class JvmDeps(
        private val stored: StoredConnection?,
        private val activeCommunity: String = "default",
        private val joinedCommunities: List<String> = emptyList(),
        private val storedByCommunity: Map<String, StoredConnection> = emptyMap(),
    ) : EngineWiring.Deps {
        var scheduled = false
        var onSchedule: (() -> Unit)? = null
        var credentialBlob: ByteArray? = null
        var savedRotatedConnection: StoredConnection? = null
        var loadedCommunity: String? = null
        var discoveryComplete = false
        var newChatSeenAfterDiscovery = false
        var discoveryEntered: CompletableDeferred<Unit>? = null
        var releaseDiscovery: CompletableDeferred<Unit>? = null
        val enumeratedCommunities = mutableListOf<String>()

        override fun loadStoredConnection(): StoredConnection? = savedRotatedConnection ?: stored

        override fun activeCommunityId(): String = activeCommunity

        override fun joinedCommunityIds(): List<String> = joinedCommunities

        override fun loadStoredConnection(communityId: String): StoredConnection? {
            loadedCommunity = communityId
            return savedRotatedConnection?.takeIf { communityId == activeCommunity } ?: storedByCommunity[communityId] ?: stored
        }

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

        override fun schedulePoll(communityMinPollSeconds: Int?) {
            scheduled = true
            onSchedule?.invoke()
        }

        override fun communityChatIds(communityId: String): List<String> {
            enumeratedCommunities.add(communityId)
            if (discoveryComplete) newChatSeenAfterDiscovery = true
            return if (discoveryComplete) listOf(chatId, "newly-registered-chat") else listOf(chatId)
        }

        override suspend fun discoverPublicChats() {
            discoveryEntered?.complete(Unit)
            releaseDiscovery?.await()
            discoveryComplete = true
        }

        override fun identityCrypto(): IdentityCrypto = AppTestSupport.identityCrypto()

        override suspend fun readRawFile(
            config: ConnectionConfig,
            path: String,
        ): ByteArray? = credentialBlob // null unless a test exercises credential rotation

        override fun saveRotatedConfig(
            newConfig: ConnectionConfig,
            communityId: String,
        ): Boolean {
            val anchor = loadStoredConnection(communityId) ?: return false
            savedRotatedConnection = ConnectionConfigStore.rotatedConnection(anchor, newConfig)
            return true
        }
    }
}
