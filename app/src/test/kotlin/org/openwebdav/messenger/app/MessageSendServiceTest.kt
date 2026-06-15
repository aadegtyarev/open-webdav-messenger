package org.openwebdav.messenger.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
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
import org.openwebdav.messenger.message.MessageEnvelope
import org.openwebdav.messenger.protocol.ChatPaths
import org.openwebdav.messenger.protocol.Hex
import org.openwebdav.messenger.protocol.MessageId
import org.openwebdav.messenger.sync.ChatSubscription
import org.openwebdav.messenger.sync.FakeDisk
import org.openwebdav.messenger.sync.SyncEngine
import org.openwebdav.messenger.sync.SyncTestSupport
import org.openwebdav.messenger.transport.WebDavTransport
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * JVM tests for the send path ([MessageSendService]) over the real engine seams + a FakeDisk-backed
 * MockWebServer + in-memory Room (`ui-chat-surface` plan Test plan): immediate local echo + exactly one
 * shared-log write, and send-then-poll dedup to one feed row. All NEW; the substrate seams are consumed
 * unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class MessageSendServiceTest {
    private lateinit var server: MockWebServer
    private lateinit var disk: FakeDisk
    private lateinit var db: MessengerDatabase
    private lateinit var identity: Identity
    private val chatId = SyncTestSupport.CHAT_ID
    private val chatKey: ChatKey = SyncTestSupport.fixedChatKey()

    @Before
    fun setUp() {
        server = MockWebServer()
        disk = FakeDisk()
        server.dispatcher = disk
        server.start()
        db = SyncTestSupport.inMemoryDb()
        identity = AppTestSupport.newIdentity()
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    private fun transport(): WebDavTransport = SyncTestSupport.transport(server)

    private fun store(): MessageStore = SyncTestSupport.store(db)

    private fun graph(store: MessageStore): RuntimeGraph {
        val envelope = MessageEnvelope.create(MessageCrypto(Aead(AppTestSupport.native())), AppTestSupport.identityCrypto())
        val engine =
            SyncEngine(
                transport = transport(),
                envelope = envelope,
                store = store,
                keyProvider = { requested -> if (requested == chatId) chatKey else null },
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

    /**
     * send_persists_local_echo_immediately_and_writes_log_once — sending text persists a local row at once
     * and issues exactly one shared-log write (allMembers=[self] ⇒ no change-index notes).
     */
    @Test
    fun send_persists_local_echo_immediately_and_writes_log_once() =
        runTest {
            val store = store()
            val service = MessageSendService(graph(store), ioDispatcher = Dispatchers.Unconfined, clock = { 1_717_000_000_000L })

            val result = service.send("hello world")

            assertTrue(result.logWritten)
            // Exactly one local echo row, immediately.
            val rows = store.messagesForChat(chatId)
            assertEquals(1, rows.size)
            assertEquals("hello world", rows.single().body)
            assertEquals(result.messageId, rows.single().messageId)
            // Exactly one shared-log file; NO change-index notes (roster is [self] only).
            assertEquals(1, disk.fileNames(ChatPaths.LOG).size)
        }

    /**
     * send_then_background_poll_dedups_to_one_row — after a send (local echo), a poll that re-lists the
     * same log entry resolves to exactly ONE feed row (dedup on the §2 message-id).
     */
    @Test
    fun send_then_background_poll_dedups_to_one_row() =
        runTest {
            val store = store()
            val g = graph(store)
            val service = MessageSendService(g, ioDispatcher = Dispatchers.Unconfined, clock = { 1_717_000_000_000L })

            val sent = service.send("only once")
            assertEquals(1, store.messagesForChat(chatId).size)

            // Make the just-sent log entry visible to THIS member's change index so the poll fetches it,
            // then run a real poll cycle: the re-fetched message has the same §2 id → no duplicate row.
            val (orderToken, _) = MessageId.splitMessageId(sent.messageId)!!
            val indexPath = ChatPaths.changeIndex(g.senderIdentifier, chatId)
            disk.putFile("$indexPath/${SyncTestSupport.changeEntryName(chatId, orderToken)}", byteArrayOf(0))

            val outcome = g.engine.pollCycle(g.senderIdentifier, listOf(ChatSubscription(chatId)))

            // The poll re-fetched the entry but it dedups against the local echo → still one row.
            assertEquals(0, outcome.newCount)
            assertEquals(1, store.messagesForChat(chatId).size)
        }
}
