package org.openwebdav.messenger.app

import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.openwebdav.messenger.crypto.Aead
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.crypto.MessageCrypto
import org.openwebdav.messenger.data.MessageStore
import org.openwebdav.messenger.keystore.StoredConnection
import org.openwebdav.messenger.message.MessageEnvelope
import org.openwebdav.messenger.protocol.Hex
import org.openwebdav.messenger.sync.FakeDisk
import org.openwebdav.messenger.sync.SyncEngine
import org.openwebdav.messenger.sync.SyncTestSupport
import org.openwebdav.messenger.transport.ConnectionConfig
import org.openwebdav.messenger.transport.TransportFactory
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SelectedCommunityGroupContextTest {
    private val server =
        MockWebServer().apply {
            dispatcher = FakeDisk()
            start()
        }
    private val db = SyncTestSupport.inMemoryDb()
    private val identity = AppTestSupport.newIdentity()
    private val chatKey: ChatKey = SyncTestSupport.fixedChatKey()

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    @Test
    fun production_context_resolver_rejects_a_graph_from_another_community_root() {
        val configA = SyncTestSupport.config(server).copy(baseUrl = "https://root-a.example.test")
        val configB = SyncTestSupport.config(server).copy(baseUrl = "https://root-b.example.test")
        val graphA = graph("community-a", configA)
        val graphB = graph("community-b", configB)
        val storedB = StoredConnection(configB, "community-chat-b", "Community B")

        val context =
            selectedCommunityGroupContext(
                communityId = "community-b",
                activeCommunityId = "community-b",
                stored = storedB,
                graph = graphB,
                selectionRevision = 12,
            )

        assertSame(graphB, context?.graph)
        assertSame(identity, context?.graph?.identity)
        assertEquals("https://root-b.example.test", context?.stored?.config?.baseUrl)
        assertEquals("community-b", context?.communityId)
        assertEquals(12L, context?.selectionRevision)
        assertNull(selectedCommunityGroupContext("community-b", "community-b", storedB, graphA, 13))
    }

    private fun graph(
        communityId: String,
        config: ConnectionConfig,
    ): RuntimeGraph {
        val store = MessageStore(db.messageDao(), db.syncCursorDao(), communityId)
        val envelope = MessageEnvelope.create(MessageCrypto(Aead(AppTestSupport.native())), AppTestSupport.identityCrypto())
        val engine =
            SyncEngine(
                transport = TransportFactory.create(config),
                envelope = envelope,
                store = store,
                keyProvider = { chatKey },
            )
        return RuntimeGraph(
            engine = engine,
            store = store,
            envelope = envelope,
            config = config,
            chatId = "community-chat-$communityId",
            communityName = communityId,
            chatKey = chatKey,
            identity = identity,
            senderIdentifier = Hex.encode(identity.copySignPublic()),
            communityId = communityId,
        )
    }
}
