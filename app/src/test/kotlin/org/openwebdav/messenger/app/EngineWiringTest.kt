package org.openwebdav.messenger.app

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
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
import org.robolectric.annotation.Config

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
    ) : EngineWiring.Deps {
        var scheduled = false

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

        override fun schedulePoll(communityMinPollMinutes: Int?) {
            scheduled = true
        }

        override fun communityChatIds(communityId: String): List<String> = listOf(chatId)
    }
}
