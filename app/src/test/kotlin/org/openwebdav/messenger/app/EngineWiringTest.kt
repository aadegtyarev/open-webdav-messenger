package org.openwebdav.messenger.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.openwebdav.messenger.account.AccountMutationBarrier
import org.openwebdav.messenger.crypto.Aead
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.crypto.MessageCrypto
import org.openwebdav.messenger.data.MessageEntity
import org.openwebdav.messenger.data.MessageStore
import org.openwebdav.messenger.data.MessengerDatabase
import org.openwebdav.messenger.directory.CredentialRotation
import org.openwebdav.messenger.directory.DirectoryEntry
import org.openwebdav.messenger.directory.DirectoryReadResult
import org.openwebdav.messenger.identity.Identity
import org.openwebdav.messenger.identity.IdentityCrypto
import org.openwebdav.messenger.keystore.ChatRegistry
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
import org.robolectric.RuntimeEnvironment
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

    private class CountingRosterPersistence : VerifiedRosterCachePersistence {
        private val records =
            java.util.concurrent.ConcurrentHashMap<Pair<String, String>, CachedVerifiedRoster>()
        val loads =
            java.util.concurrent.ConcurrentHashMap<Pair<String, String>, java.util.concurrent.atomic.AtomicInteger>()

        override fun load(
            communityId: String,
            chatId: String,
        ): CachedVerifiedRoster? {
            loads.computeIfAbsent(communityId to chatId) { java.util.concurrent.atomic.AtomicInteger() }.incrementAndGet()
            return records[communityId to chatId]
        }

        override fun put(entry: CachedVerifiedRoster) {
            records[entry.communityId to entry.chatId] = entry
        }

        override fun remove(
            communityId: String,
            chatId: String,
        ) {
            records.remove(communityId to chatId)
        }

        override fun clear() {
            records.clear()
        }
    }

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
        AppContainer.clearChatOpenTestSeam()
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
                    storedByCommunity = mapOf("community-b" to stored),
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
            EngineWiring.switchToChat(
                "opened-group",
                "Project group",
                chatKey,
                listOf(selfId, peerId),
                mapOf(peerId to "Project peer"),
                RecipientReadiness.Loading,
            )
            val oldGraph = EngineWiring.current()!!
            val oldReadStarted = CompletableDeferred<Unit>()
            val oldRead = CompletableDeferred<DirectoryReadResult>()
            val oldEnrichment =
                RecipientRosterEnricher(
                    this,
                    oldGraph,
                    { update -> EngineWiring.updateGraphIfCurrent(oldGraph, { EngineWiring.current() === oldGraph }, update) },
                    read = {
                        oldReadStarted.complete(Unit)
                        oldRead.await()
                    },
                ).start()
            oldReadStarted.await()

            SyncRunner.current().runOnce()

            val rotatedGraph = EngineWiring.current()!!
            assertTrue(rotatedGraph.recipientSnapshot() is RecipientReadiness.Unavailable)
            oldRead.complete(DirectoryReadResult(listOf(DirectoryEntry("Stale", ByteArray(32) { 3 }, ByteArray(32) { 4 })), 0))
            oldEnrichment.join()
            assertTrue(rotatedGraph.recipientSnapshot() is RecipientReadiness.Unavailable)
            rotatedGraph.updateRecipientReadiness(RecipientReadiness.Ready(listOf(selfId, peerId)))
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
    fun host_rotations_on_different_owners_progress_concurrently_without_cross_writes() =
        runTest {
            val anchorA = StoredConnection(SyncTestSupport.config(server), chatId, "Community A")
            val serverB =
                MockWebServer().apply {
                    dispatcher = FakeDisk()
                    start()
                }
            try {
                val anchorB = StoredConnection(SyncTestSupport.config(serverB), "anchor-b", "Community B")
                val storedByCommunity = mutableMapOf("community-a" to anchorA, "community-b" to anchorB)
                val keys = mapOf(anchorA.chatId to chatKey, anchorB.chatId to chatKey)
                val deps =
                    JvmDeps(
                        stored = anchorA,
                        activeCommunity = "community-a",
                        storedByCommunity = mapOf("community-b" to anchorB),
                    )
                EngineWiring.initialize(deps)
                val readStarted = CompletableDeferred<Unit>()
                val releaseDirectory = CompletableDeferred<List<DirectoryEntry>>()
                val publishStarted = CompletableDeferred<Unit>()
                val releasePublish = CompletableDeferred<Boolean>()
                val bPublicationStarted = CompletableDeferred<Unit>()
                val peer = AppTestSupport.newIdentity()
                val publishedOwners = mutableListOf<String>()
                val memberReads = mutableListOf<String>()
                val selectedOwners = mutableListOf<String>()
                AppContainer.configureCredentialRotationTestSeam(
                    "community-a",
                    AppContainer.CredentialRotationTestSeam(
                        loadStored = storedByCommunity::get,
                        loadChatKey = keys::get,
                        readDirectory = { stored, _ ->
                            memberReads += if (stored == anchorA) "community-a" else "community-b"
                            readStarted.complete(Unit)
                            releaseDirectory.await()
                        },
                        writeCredential = { owner, config, _, blob ->
                            publishedOwners += owner
                            assertEquals(storedByCommunity.getValue(owner).config, config)
                            if (owner == "community-a") {
                                publishStarted.complete(Unit)
                                releasePublish.await() && blob.isNotEmpty()
                            } else {
                                bPublicationStarted.complete(Unit)
                                blob.isNotEmpty()
                            }
                        },
                        saveStored = { owner, stored ->
                            storedByCommunity[owner] = stored
                            true
                        },
                        onCommunitySelected = selectedOwners::add,
                    ),
                )

                val rotation =
                    async {
                        AppContainer.rotateCredential(
                            newUrl = "https://rotated-a.example.test",
                            newUsername = "rotated-a",
                            newPassword = "secret-a",
                            excludeMemberSignPub = "excluded-member",
                        )
                    }
                readStarted.await()
                assertTrue(AppContainer.switchToCommunity("community-b"))
                releaseDirectory.complete(listOf(DirectoryEntry("Peer", peer.copySignPublic(), peer.copyBoxPublic())))
                publishStarted.await()
                assertTrue(AppContainer.switchToCommunity("community-a"))
                assertTrue(AppContainer.switchToCommunity("community-b"))
                val rotatedB =
                    async {
                        AppContainer.rotateCredential(
                            newUrl = "https://rotated-b.example.test",
                            newUsername = "rotated-b",
                            newPassword = "secret-b",
                            excludeMemberSignPub = "excluded-member",
                        )
                    }
                bPublicationStarted.await()
                assertTrue(rotatedB.await())
                val graphB = EngineWiring.current()!!
                graphB.updateRecipientReadiness(RecipientReadiness.Unavailable("community B readiness sentinel"))
                val graphBReadiness = graphB.recipientSnapshot()
                releasePublish.complete(true)

                assertTrue(rotation.await())
                assertEquals(
                    anchorA.copy(
                        config =
                            anchorA.config.copy(
                                baseUrl = "https://rotated-a.example.test",
                                username = "rotated-a",
                                appPassword = "secret-a",
                            ),
                    ),
                    storedByCommunity["community-a"],
                )
                assertEquals(
                    anchorB.copy(
                        config =
                            anchorB.config.copy(
                                baseUrl = "https://rotated-b.example.test",
                                username = "rotated-b",
                                appPassword = "secret-b",
                            ),
                    ),
                    storedByCommunity["community-b"],
                )
                assertEquals(listOf("community-a", "community-b"), memberReads)
                assertEquals(listOf("community-a", "community-b"), publishedOwners)
                assertEquals(listOf("community-b", "community-a", "community-b"), selectedOwners)
                assertEquals("community-b", AppContainer.activeCommunityId)
                assertSame(graphB, EngineWiring.current())
                assertEquals(anchorB.chatId, graphB.chatId)
                assertEquals(anchorB.communityName, graphB.communityName)
                assertEquals(storedByCommunity.getValue("community-b").config, graphB.config)
                assertEquals(graphBReadiness, graphB.recipientSnapshot())
            } finally {
                AppContainer.clearCredentialRotationTestSeam()
                EngineWiring.initialize(AppTestSupport.emptyEngineDeps())
                serverB.shutdown()
            }
        }

    @Test
    fun start_dm_publishes_verified_peer_projection_with_matching_recipients() =
        runBlocking {
            AppContainer.bind(RuntimeEnvironment.getApplication())
            val stored = StoredConnection(SyncTestSupport.config(server), chatId, "Community")
            EngineWiring.initialize(JvmDeps(stored = stored))
            val persistence = CountingRosterPersistence()
            AppContainer.configureChatOpenTestSeam(
                "default",
                AppContainer.ChatOpenTestSeam(
                    loadChatKey = { chatKey },
                    loadStored = { stored },
                    readDirectory = { DirectoryReadResult(emptyList(), 0) },
                    cachePersistence = persistence,
                    provisionDm = { _, _, _ -> true },
                ),
            )
            val peer = DirectoryEntry("Verified peer", ByteArray(32) { 17 }, ByteArray(32) { 18 })
            val dmId = checkNotNull(AppContainer.startDm(peer))
            val dmGraph = checkNotNull(EngineWiring.current())
            assertEquals(dmId, dmGraph.chatId)
            val ready = dmGraph.recipientSnapshot() as RecipientReadiness.Ready
            val peerId = Hex.encode(peer.copySigningPublicKey())
            assertEquals(setOf(dmGraph.senderIdentifier, peerId), ready.members.toSet())
            assertEquals(2, ready.participants.size)
            assertEquals(
                setOf(
                    participantDigest(dmGraph.identity.copySignPublic()),
                    participantDigest(peer.copySigningPublicKey()),
                ),
                ready.participants.map { it.identityDigest }.toSet(),
            )
            assertTrue(ready.participants.single { it.isSelf }.identityDigest == participantDigest(dmGraph.identity.copySignPublic()))
        }

    @Test
    fun superseded_general_continuation_keeps_original_request_and_installed_graph() {
        val generalId = "general-chat"
        val dmId = "dm-chat"
        val communityId = "community-a"
        val stored = StoredConnection(SyncTestSupport.config(server), generalId, "Community")
        val cachePersistence = CountingRosterPersistence()
        EngineWiring.initialize(AppTestSupport.chatOpenTestDeps(server, db, communityId, stored, identity, mapOf(generalId to chatKey)))
        val dmRoster = listOf(DirectoryEntry("DM peer", ByteArray(32) { 4 }, ByteArray(32) { 5 }))
        val provenance = RosterCacheProvenance.digest(communityId, dmId, chatKey, chatKey, identity)
        cachePersistence.put(CachedVerifiedRoster(communityId, dmId, provenance, dmRoster))
        val installed = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val directoryRead = CountDownLatch(1)
        AppContainer.configureChatOpenTestSeam(
            communityId,
            AppContainer.ChatOpenTestSeam(
                loadChatKey = { chatKey },
                loadStored = { stored },
                readDirectory = {
                    directoryRead.countDown()
                    CompletableDeferred<DirectoryReadResult>().await()
                },
                cachePersistence = cachePersistence,
                chatKind = { _, id -> if (id == dmId) "dm" else "general" },
                beforeGeneralRosterPreparation = {
                    installed.countDown()
                    check(resume.await(5, TimeUnit.SECONDS))
                },
            ),
        )
        val executor = Executors.newSingleThreadExecutor()
        try {
            val t1 = AppContainer.beginChatOpenRequest()
            val generalOpen =
                executor.submit<Boolean> {
                    kotlinx.coroutines.runBlocking { AppContainer.switchToCommunity(communityId, t1) }
                }
            assertTrue(installed.await(5, TimeUnit.SECONDS))
            val t2 = AppContainer.beginChatOpenRequest()
            assertTrue(kotlinx.coroutines.runBlocking { AppContainer.openGroupChat(dmId, "DM", communityId, requestToken = t2) })
            val dmGraph = checkNotNull(EngineWiring.current())
            assertEquals(dmId, dmGraph.chatId)
            assertTrue(dmGraph.recipientSnapshot() is RecipientReadiness.Ready)
            assertEquals("DM peer", dmGraph.memberNames.values.single())
            val participantSnapshot = dmGraph.recipientSnapshot() as RecipientReadiness.Ready
            assertTrue(participantSnapshot.participants.any { it.displayName == "DM peer" && !it.isSelf })
            assertTrue(participantSnapshot.participants.any { it.isSelf })
            assertTrue(directoryRead.await(5, TimeUnit.SECONDS))
            resume.countDown()
            assertTrue(generalOpen.get(5, TimeUnit.SECONDS))
            assertSame(dmGraph, EngineWiring.current())
            assertTrue(dmGraph.recipientSnapshot() is RecipientReadiness.Ready)
            assertEquals("DM peer", dmGraph.memberNames.values.single())
            assertEquals(1, cachePersistence.loads[communityId to dmId]?.get())
        } finally {
            resume.countDown()
            executor.shutdownNow()
            AppContainer.clearChatOpenTestSeam()
            EngineWiring.initialize(AppTestSupport.emptyEngineDeps())
        }
    }

    @Test
    fun concurrent_same_owner_host_rotations_publish_then_commit_in_serial_order() =
        runTest {
            val anchor = StoredConnection(SyncTestSupport.config(server), chatId, "Community A")
            val durable = mutableMapOf("community-a" to anchor)
            val deps = JvmDeps(stored = anchor, activeCommunity = "community-a")
            EngineWiring.initialize(deps)
            val peer = AppTestSupport.newIdentity()
            val directoryReads = mutableListOf<ConnectionConfig>()
            val secondDirectoryRead = CompletableDeferred<Unit>()
            val firstPublication = CompletableDeferred<Unit>()
            val releaseFirstPublication = CompletableDeferred<Unit>()
            val publishedBlobs = mutableListOf<ByteArray>()
            val publishedSourceConfigs = mutableListOf<ConnectionConfig>()
            val configX =
                anchor.config.copy(
                    baseUrl = "https://rotation-x.example.test",
                    username = "rotation-x",
                    appPassword = "secret-x",
                )
            val configY =
                anchor.config.copy(
                    baseUrl = "https://rotation-y.example.test",
                    username = "rotation-y",
                    appPassword = "secret-y",
                )
            AppContainer.configureCredentialRotationTestSeam(
                "community-a",
                AppContainer.CredentialRotationTestSeam(
                    loadStored = durable::get,
                    loadChatKey = { chatKey },
                    readDirectory = { stored, _ ->
                        directoryReads += stored.config
                        if (directoryReads.size == 2) secondDirectoryRead.complete(Unit)
                        listOf(DirectoryEntry("Peer", peer.copySignPublic(), peer.copyBoxPublic()))
                    },
                    writeCredential = { owner, sourceConfig, _, blob ->
                        assertEquals("community-a", owner)
                        publishedSourceConfigs += sourceConfig
                        if (sourceConfig == anchor.config) {
                            firstPublication.complete(Unit)
                            releaseFirstPublication.await()
                        }
                        publishedBlobs += blob
                        true
                    },
                    saveStored = { owner, stored ->
                        durable[owner] = stored
                        true
                    },
                ),
            )
            try {
                val first =
                    async {
                        AppContainer.rotateCredential(
                            "https://rotation-x.example.test",
                            "rotation-x",
                            "secret-x",
                            "excluded",
                        )
                    }
                firstPublication.await()
                val second =
                    async {
                        AppContainer.rotateCredential(
                            "https://rotation-y.example.test",
                            "rotation-y",
                            "secret-y",
                            "excluded",
                        )
                    }
                yield()
                assertFalse(secondDirectoryRead.isCompleted)

                releaseFirstPublication.complete(Unit)
                assertTrue(first.await())
                secondDirectoryRead.await()
                assertTrue(second.await())

                assertEquals(listOf(anchor.config, configX), publishedSourceConfigs)
                assertEquals(configY, durable.getValue("community-a").config)
                val remoteConfig =
                    CredentialRotation.openForMember(
                        blob = publishedBlobs.last(),
                        identity = peer,
                        identityCrypto = AppTestSupport.identityCrypto(),
                    )
                assertEquals(durable.getValue("community-a").config, remoteConfig)
            } finally {
                AppContainer.clearCredentialRotationTestSeam()
                EngineWiring.initialize(AppTestSupport.emptyEngineDeps())
            }
        }

    @Test
    fun host_rotation_aborts_after_restore_replaces_captured_account_during_network_read() =
        runTest {
            val anchor = StoredConnection(SyncTestSupport.config(server), chatId, "Community A")
            val durable = mutableMapOf("community-a" to anchor)
            val deps = JvmDeps(stored = anchor, activeCommunity = "community-a")
            EngineWiring.initialize(deps)
            val readStarted = CompletableDeferred<Unit>()
            val releaseDirectory = CompletableDeferred<List<DirectoryEntry>>()
            val peer = AppTestSupport.newIdentity()
            var saveAttempts = 0
            AppContainer.configureCredentialRotationTestSeam(
                "community-a",
                AppContainer.CredentialRotationTestSeam(
                    loadStored = durable::get,
                    loadChatKey = { chatKey },
                    readDirectory = { _, _ ->
                        readStarted.complete(Unit)
                        releaseDirectory.await()
                    },
                    writeCredential = { _, _, _, blob -> blob.isNotEmpty() },
                    saveStored = { owner, stored ->
                        saveAttempts++
                        durable[owner] = stored
                        true
                    },
                ),
            )
            try {
                val rotation =
                    async {
                        AppContainer.rotateCredential(
                            newUrl = "https://stale-rotation.example.test",
                            newUsername = "stale-rotation",
                            newPassword = "stale-secret",
                            excludeMemberSignPub = "excluded-member",
                        )
                    }
                readStarted.await()
                val restored = anchor.copy(config = anchor.config.copy(username = "restored-account"), chatId = "restored-anchor")
                AccountMutationBarrier.process.withExclusive {
                    AccountMutationBarrier.process.withAccountReplacement {
                        durable["community-a"] = restored
                        EngineWiring.reconfigure(
                            config = restored.config,
                            chatId = restored.chatId,
                            communityName = restored.communityName,
                            chatKey = chatKey,
                            identity = identity,
                            communityId = "community-a",
                        )
                    }
                }
                val restoredGraph = EngineWiring.current()!!
                releaseDirectory.complete(listOf(DirectoryEntry("Peer", peer.copySignPublic(), peer.copyBoxPublic())))

                assertFalse(rotation.await())
                assertEquals(0, saveAttempts)
                assertEquals(restored, durable["community-a"])
                assertSame(restoredGraph, EngineWiring.current())
                assertEquals(restored.chatId, EngineWiring.current()?.chatId)
                assertEquals(restored.config, EngineWiring.current()?.config)
            } finally {
                AppContainer.clearCredentialRotationTestSeam()
                EngineWiring.initialize(AppTestSupport.emptyEngineDeps())
            }
        }

    @Test
    fun non_host_rotation_after_switch_to_b_updates_only_captured_community_a() =
        runTest {
            val anchorA = StoredConnection(SyncTestSupport.config(server), chatId, "Community A")
            val serverB =
                MockWebServer().apply {
                    dispatcher = FakeDisk()
                    start()
                }
            try {
                val anchorB = StoredConnection(SyncTestSupport.config(serverB), "anchor-b", "Community B")
                val host = AppTestSupport.newIdentity()
                val rotated = anchorA.config.copy(username = "rotated-a", appPassword = "secret-a")
                val deps =
                    JvmDeps(
                        stored = anchorA,
                        activeCommunity = "community-a",
                        joinedCommunities = listOf("community-a", "community-b"),
                        storedByCommunity = mapOf("community-b" to anchorB),
                    )
                deps.credentialBlob =
                    CredentialRotation.sealForMember(
                        rotated, identity.copyBoxPublic(), AppTestSupport.identityCrypto(), host,
                    )
                EngineWiring.initialize(deps)
                val capturedRunnerA = SyncRunner.current()
                val readStarted = CompletableDeferred<Unit>()
                val releaseRead = CompletableDeferred<ByteArray?>()
                deps.credentialReadStarted = readStarted
                deps.releaseCredentialRead = releaseRead

                val rotation = async { capturedRunnerA.runOnce() }
                readStarted.await()
                EngineWiring.reconfigure(
                    anchorB.config,
                    anchorB.chatId,
                    anchorB.communityName,
                    chatKey,
                    identity,
                    communityId = "community-b",
                )
                val graphB = EngineWiring.current()!!
                val bSnapshot = graphB.recipientSnapshot()
                releaseRead.complete(deps.credentialBlob)
                rotation.await()

                assertEquals(anchorA.copy(config = rotated), deps.savedRotatedConnections["community-a"])
                assertEquals(anchorB, deps.loadStoredConnection("community-b"))
                assertNull(deps.savedRotatedConnections["community-b"])
                assertSame(graphB, EngineWiring.current())
                assertEquals("community-b", EngineWiring.current()?.communityId)
                assertEquals(anchorB.chatId, EngineWiring.current()?.chatId)
                assertEquals(anchorB.config, EngineWiring.current()?.config)
                assertEquals(bSnapshot, EngineWiring.current()?.recipientSnapshot())
            } finally {
                EngineWiring.initialize(AppTestSupport.emptyEngineDeps())
                serverB.shutdown()
            }
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
        val requests = ChatOpenRequestCoordinator()
        val keyStore = InMemoryChatKeyStore()
        var selectedCommunity = "community-a"
        var activations = 0
        val coordinator =
            GroupChatOpenCoordinator(
                selectionGuard = guard,
                requestCoordinator = requests,
                currentCommunityId = { selectedCommunity },
                loadChatKey = keyStore::load,
                activateCommunity = { communityId, _ ->
                    activations++
                    selectedCommunity = communityId
                    true
                },
                currentGraph = EngineWiring::current,
            )
        val initialRevision = guard.current()
        val missingKeyRequest = requests.begin()

        assertNull(coordinator.prepare("missing-key-group", "Broken", "community-a", null, missingKeyRequest))
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
        val validPlan =
            coordinator.prepare("valid-group", "Valid group", "community-a", null, requests.begin())!!

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
                recipientReadiness = RecipientReadiness.Loading,
            ),
        )
        assertTrue(generalGraph.recipientSnapshot() is RecipientReadiness.Ready)
        assertEquals(generalGraph.communityId, EngineWiring.current()?.communityId)
        assertEquals("valid-group", EngineWiring.current()?.chatId)
        assertEquals(RecipientReadiness.Loading, EngineWiring.current()?.recipientSnapshot())
    }

    @Test
    fun same_community_group_creation_stays_bound_to_its_captured_runtime() =
        runTest {
            val config = SyncTestSupport.config(server)
            val stored = StoredConnection(config, "general-a", "A")
            EngineWiring.initialize(JvmDeps(stored = stored, activeCommunity = "community-a"))
            val initialGraph = EngineWiring.current()!!
            val requests = ChatOpenRequestCoordinator()
            val request = requests.begin()
            val guard = RuntimeSelectionGuard()
            var selectedCommunity = "community-a"
            val result =
                createGroupInSelectedCommunity(
                    communityId = "community-a",
                    activeCommunityId = selectedCommunity,
                    isRequestCurrent = { requests.isCurrent(request) },
                    isRuntimeCurrent = { EngineWiring.current()?.communityRuntimeKey == initialGraph.communityRuntimeKey },
                    isSelectedContextCurrent = { context ->
                        requests.isCurrent(request) &&
                            selectedCommunity == context.communityId &&
                            guard.isCurrent(context.selectionRevision) &&
                            EngineWiring.current() === context.graph
                    },
                    activateCommunity = { error("same-community creation must not activate") },
                    resolveContext = { communityId ->
                        selectedCommunityGroupContext(
                            communityId,
                            selectedCommunity,
                            stored,
                            EngineWiring.current(),
                            guard.current(),
                        )
                    },
                    create = { "group-a" },
                    open = { context, chatId ->
                        requests.runIfCurrent(request) {
                            EngineWiring.switchToChatIfCurrent(
                                guard,
                                context.selectionRevision,
                                context.graph,
                                chatId,
                                "Group A",
                                chatKey,
                                listOf(context.graph.senderIdentifier),
                                emptyMap(),
                                { selectedCommunity == context.communityId },
                            )
                        }
                    },
                )

            assertEquals("group-a", result)
            assertEquals("community-a", EngineWiring.current()?.communityId)
            assertEquals("group-a", EngineWiring.current()?.chatId)
        }

    @Test
    fun group_creation_activates_and_opens_using_the_new_cross_community_runtime() =
        runTest {
            val config = SyncTestSupport.config(server)
            val storedA = StoredConnection(config, "general-a", "A")
            val storedB = StoredConnection(config, "general-b", "B")
            EngineWiring.initialize(JvmDeps(stored = storedA, activeCommunity = "community-a"))
            val initialGraph = EngineWiring.current()!!
            val requests = ChatOpenRequestCoordinator()
            val request = requests.begin()
            val guard = RuntimeSelectionGuard()
            var selectedCommunity = "community-a"
            var createdCommunity: String? = null
            val registry = ChatRegistry(RuntimeEnvironment.getApplication())
            registry.clear("community-b")
            val result =
                createGroupInSelectedCommunity(
                    communityId = "community-b",
                    activeCommunityId = selectedCommunity,
                    isRequestCurrent = { requests.isCurrent(request) },
                    isRuntimeCurrent = { EngineWiring.current()?.communityRuntimeKey == initialGraph.communityRuntimeKey },
                    isSelectedContextCurrent = { context ->
                        requests.isCurrent(request) &&
                            selectedCommunity == context.communityId &&
                            guard.isCurrent(context.selectionRevision) &&
                            EngineWiring.current() === context.graph
                    },
                    activateCommunity = { communityId ->
                        requests.runIfCurrentSerialized(request) {
                            guard.begin()
                            selectedCommunity = communityId
                            EngineWiring.reconfigure(
                                config,
                                storedB.chatId,
                                storedB.communityName,
                                chatKey,
                                identity,
                                communityId,
                            )
                            true
                        }
                    },
                    resolveContext = { communityId ->
                        selectedCommunityGroupContext(
                            communityId,
                            selectedCommunity,
                            storedB,
                            EngineWiring.current(),
                            guard.current(),
                        )
                    },
                    create = { context ->
                        assertEquals("community-b", context.graph.communityId)
                        assertNotEquals(initialGraph.communityRuntimeKey, context.graph.communityRuntimeKey)
                        registry.add(context.communityId, ChatRegistry.Entry("group-b", "Group B", "group"))
                        createdCommunity = context.communityId
                        "group-b"
                    },
                    open = { context, chatId ->
                        requests.runIfCurrent(request) {
                            EngineWiring.switchToChatIfCurrent(
                                guard,
                                context.selectionRevision,
                                context.graph,
                                chatId,
                                "Group B",
                                chatKey,
                                listOf(context.graph.senderIdentifier),
                                emptyMap(),
                                { selectedCommunity == context.communityId },
                            )
                        }
                    },
                )

            assertEquals("group-b", result)
            assertEquals("community-b", createdCommunity)
            assertEquals("community-b", selectedCommunity)
            assertEquals("community-b", EngineWiring.current()?.communityId)
            assertEquals("group-b", EngineWiring.current()?.chatId)
            assertEquals(listOf(ChatRegistry.Entry("group-b", "Group B", "group")), registry.all("community-b"))
            registry.clear("community-b")
        }

    @Test
    fun group_creation_does_not_open_against_an_intervening_runtime_reconfigure() =
        runTest {
            val config = SyncTestSupport.config(server)
            val storedA = StoredConnection(config, "general-a", "A")
            val storedB = StoredConnection(config, "general-b", "B")
            EngineWiring.initialize(JvmDeps(stored = storedA, activeCommunity = "community-a"))
            val initialGraph = EngineWiring.current()!!
            val requests = ChatOpenRequestCoordinator()
            val request = requests.begin()
            val guard = RuntimeSelectionGuard()
            val publishStarted = CompletableDeferred<Unit>()
            val finishPublish = CompletableDeferred<String?>()
            var selectedCommunity = "community-a"
            var opens = 0

            val creation =
                async {
                    createGroupInSelectedCommunity(
                        communityId = "community-b",
                        activeCommunityId = selectedCommunity,
                        isRequestCurrent = { requests.isCurrent(request) },
                        isRuntimeCurrent = { EngineWiring.current()?.communityRuntimeKey == initialGraph.communityRuntimeKey },
                        isSelectedContextCurrent = { context ->
                            requests.isCurrent(request) &&
                                selectedCommunity == context.communityId &&
                                guard.isCurrent(context.selectionRevision) &&
                                EngineWiring.current() === context.graph
                        },
                        activateCommunity = { communityId ->
                            requests.runIfCurrentSerialized(request) {
                                guard.begin()
                                selectedCommunity = communityId
                                EngineWiring.reconfigure(
                                    config,
                                    storedB.chatId,
                                    storedB.communityName,
                                    chatKey,
                                    identity,
                                    communityId,
                                )
                                true
                            }
                        },
                        resolveContext = { communityId ->
                            selectedCommunityGroupContext(
                                communityId,
                                selectedCommunity,
                                storedB,
                                EngineWiring.current(),
                                guard.current(),
                            )
                        },
                        create = {
                            publishStarted.complete(Unit)
                            finishPublish.await()
                        },
                        open = { _, _ ->
                            opens++
                            true
                        },
                    )
                }
            publishStarted.await()
            val contextB = EngineWiring.current()!!
            guard.begin()
            selectedCommunity = "community-c"
            EngineWiring.reconfigure(config, "general-c", "C", chatKey, identity, "community-c")
            val graphC = EngineWiring.current()!!
            finishPublish.complete("group-b")

            assertNull(creation.await())
            assertEquals("community-c", selectedCommunity)
            assertEquals("community-c", graphC.communityId)
            assertEquals(graphC, EngineWiring.current())
            assertNotEquals(contextB.communityRuntimeKey, EngineWiring.current()?.communityRuntimeKey)
            assertNotEquals(initialGraph.communityRuntimeKey, EngineWiring.current()?.communityRuntimeKey)
            assertEquals(0, opens)
            assertTrue(requests.isCurrent(request))
        }

    @Test
    fun queued_general_open_is_rejected_after_cross_community_group_install() {
        val requests = ChatOpenRequestCoordinator()
        val guard = RuntimeSelectionGuard()
        var selectedCommunity = "community-a"
        EngineWiring.initialize(
            JvmDeps(
                stored = StoredConnection(SyncTestSupport.config(server), chatId, "A"),
                activeCommunity = "community-a",
            ),
        )
        val delayedGeneral = requests.begin()
        val latestGroup = requests.begin()
        val groupKey = InMemoryChatKeyStore().also { it.store("group-b", chatKey) }
        val groups =
            GroupChatOpenCoordinator(
                guard,
                requests,
                { selectedCommunity },
                groupKey::load,
                { communityId, _ ->
                    selectedCommunity = communityId
                    guard.begin()
                    EngineWiring.reconfigure(
                        SyncTestSupport.config(server),
                        "general-b",
                        "B",
                        chatKey,
                        identity,
                        communityId,
                    )
                    true
                },
                EngineWiring::current,
            )
        val plan = groups.prepare("group-b", "Group B", "community-b", null, latestGroup)!!
        assertTrue(
            requests.runIfCurrent(latestGroup) {
                EngineWiring.switchToChatIfCurrent(
                    guard = guard,
                    expectedSelectionRevision = plan.selectionRevision,
                    expectedGraph = plan.graph,
                    chatId = plan.chatId,
                    chatName = plan.chatName,
                    chatKey = plan.chatKey,
                    roster = listOf(plan.graph.senderIdentifier),
                    memberNames = emptyMap(),
                    isCommunitySelected = { selectedCommunity == plan.communityId },
                )
            },
        )
        val installedGraph = EngineWiring.current()
        val installedRevision = guard.current()

        assertFalse(
            requests.runIfCurrentSerialized(delayedGeneral) {
                selectedCommunity = "community-a"
                guard.begin()
                true
            },
        )
        assertEquals("community-b", selectedCommunity)
        assertEquals(installedRevision, guard.current())
        assertEquals(installedGraph, EngineWiring.current())
    }

    @Test
    fun queued_group_open_is_rejected_after_cross_community_general_install() {
        val requests = ChatOpenRequestCoordinator()
        val guard = RuntimeSelectionGuard()
        var selectedCommunity = "community-a"
        EngineWiring.initialize(
            JvmDeps(
                stored = StoredConnection(SyncTestSupport.config(server), chatId, "A"),
                activeCommunity = "community-a",
            ),
        )
        val delayedGroup = requests.begin()
        val latestGeneral = requests.begin()
        assertTrue(
            requests.runIfCurrentSerialized(latestGeneral) {
                guard.begin()
                selectedCommunity = "community-b"
                EngineWiring.reconfigure(
                    SyncTestSupport.config(server),
                    "general-b",
                    "B",
                    chatKey,
                    identity,
                    "community-b",
                )
                true
            },
        )
        val installedGraph = EngineWiring.current()
        val installedRevision = guard.current()
        val groupKey = InMemoryChatKeyStore().also { it.store("group-a", chatKey) }
        var obsoleteActivations = 0
        val groups =
            GroupChatOpenCoordinator(
                guard,
                requests,
                { selectedCommunity },
                groupKey::load,
                { _, _ ->
                    obsoleteActivations++
                    true
                },
                EngineWiring::current,
            )

        assertNull(groups.prepare("group-a", "Group A", "community-a", null, delayedGroup))
        assertEquals(0, obsoleteActivations)
        assertEquals("community-b", selectedCommunity)
        assertEquals(installedRevision, guard.current())
        assertEquals(installedGraph, EngineWiring.current())
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
        var credentialReadStarted: CompletableDeferred<Unit>? = null
        var releaseCredentialRead: CompletableDeferred<ByteArray?>? = null
        var savedRotatedConnection: StoredConnection? = null
        val savedRotatedConnections = mutableMapOf<String, StoredConnection>()
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
            return savedRotatedConnections[communityId]
                ?: storedByCommunity[communityId]
                ?: stored?.takeIf { communityId == activeCommunity }
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
        ): ByteArray? {
            credentialReadStarted?.complete(Unit)
            return releaseCredentialRead?.await() ?: credentialBlob
        }

        override fun saveRotatedConfig(
            newConfig: ConnectionConfig,
            communityId: String,
        ): Boolean {
            val anchor = loadStoredConnection(communityId) ?: return false
            val rotated = ConnectionConfigStore.rotatedConnection(anchor, newConfig)
            savedRotatedConnections[communityId] = rotated
            if (communityId == activeCommunity) savedRotatedConnection = rotated
            return true
        }
    }
}
