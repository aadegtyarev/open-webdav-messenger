package org.openwebdav.messenger.ui.invite

import com.google.zxing.WriterException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
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
import org.openwebdav.messenger.sync.SyncEngine
import org.openwebdav.messenger.sync.SyncTestSupport
import org.openwebdav.messenger.transport.ConnectionConfig
import org.openwebdav.messenger.transport.TransportFactory
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * QR-failure fallback for [InviteViewModel] (review finding 6). When the invite token is too long for a QR
 * code, ZXing throws [WriterException]; the screen would otherwise come up permanently blank (no QR, no
 * string). The VM must still surface the copyable invite string (text-only fallback) and flag the QR
 * unavailable. A normal-sized invite still produces a QR. The graph is installed via the SAME
 * `EngineWiring.initialize` path production uses, so `AppContainer.runtimeGraph()` (which the VM reads)
 * returns it. All NEW; no existing test touched.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class InviteViewModelTest {
    private val mainDispatcher = UnconfinedTestDispatcher()
    private lateinit var server: MockWebServer
    private lateinit var db: MessengerDatabase
    private lateinit var identity: Identity
    private val chatId = "invite-chat-id-000000000001"
    private val chatKey: ChatKey = SyncTestSupport.fixedChatKey()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        server = MockWebServer()
        server.start()
        db = SyncTestSupport.inMemoryDb()
        identity = AppTestSupport.newIdentity()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        server.shutdown()
        db.close()
    }

    /** ZXing throws on a token too long for a QR — the boundary the VM guards (documents finding 6's cause). */
    @Test
    fun qr_encoder_throws_on_over_capacity_token() {
        // Far beyond QR byte capacity at EC level M — encode must throw, not silently truncate.
        val huge = "x".repeat(10_000)
        assertThrows(WriterException::class.java) { QrEncoder.encode(huge) }
    }

    /** An over-capacity invite still surfaces the copyable string + the QR-unavailable flag (no blank screen). */
    @Test
    fun over_capacity_invite_keeps_string_and_flags_qr_unavailable() =
        runTest(mainDispatcher) {
            // A high-entropy community name (random hex — gzip cannot shrink it) pushes the invite string
            // past QR byte capacity, so the QR can't render but the copyable string must still appear.
            installGraph(communityName = highEntropyName(4000))
            val vm = InviteViewModel(qrDispatcher = mainDispatcher)

            // The build runs across real IO (codec) + the injected QR dispatcher; await the resolved state.
            val state = vm.state.first { it.inviteString != null || it.error != null }
            assertNotNull("the copyable invite string must still be present", state.inviteString)
            assertTrue("the invite must carry the owdm1: scheme", state.inviteString!!.startsWith("owdm1:"))
            assertTrue("the QR must be flagged unavailable", state.qrUnavailable)
            assertNull("no QR bitmap when it could not render", state.qr)
            assertNull("a string-only fallback is not an error", state.error)
        }

    /** A normal-sized invite produces both the string and the QR. */
    @Test
    fun normal_invite_produces_string_and_qr() =
        runTest(mainDispatcher) {
            installGraph(communityName = "My Community")
            val vm = InviteViewModel(qrDispatcher = mainDispatcher)

            val state = vm.state.first { it.qr != null || it.error != null }
            assertNotNull(state.inviteString)
            assertNotNull("a normal token must render a QR", state.qr)
            assertEquals(false, state.qrUnavailable)
        }

    private fun installGraph(communityName: String) {
        EngineWiring.initialize(JvmDeps(StoredConnection(SyncTestSupport.config(server), chatId, communityName)))
    }

    /** A random hex string of [length] chars — incompressible, so it inflates the framed invite past QR limits. */
    private fun highEntropyName(length: Int): String {
        val bytes = ByteArray(length / 2)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private inner class JvmDeps(
        private val stored: StoredConnection,
    ) : EngineWiring.Deps {
        override fun loadStoredConnection(): StoredConnection = stored

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

        override fun schedulePoll(communityMinPollSeconds: Int?) = Unit

        override fun communityChatIds(communityId: String): List<String> = listOf(chatId)

        override fun identityCrypto() = AppTestSupport.identityCrypto()

        override suspend fun readRawFile(
            config: org.openwebdav.messenger.transport.ConnectionConfig,
            path: String,
        ): ByteArray? = null

        override fun saveRotatedConfig(
            newConfig: org.openwebdav.messenger.transport.ConnectionConfig,
            chatId: String,
            communityName: String,
        ): Boolean = false
    }
}
