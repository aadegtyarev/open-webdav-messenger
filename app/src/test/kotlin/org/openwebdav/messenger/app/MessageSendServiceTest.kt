package org.openwebdav.messenger.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertArrayEquals
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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

    private fun graph(
        store: MessageStore,
        webDavServer: MockWebServer = server,
        communityId: String = "default",
    ): RuntimeGraph {
        val envelope = MessageEnvelope.create(MessageCrypto(Aead(AppTestSupport.native())), AppTestSupport.identityCrypto())
        val engine =
            SyncEngine(
                transport = SyncTestSupport.transport(webDavServer),
                envelope = envelope,
                store = store,
                keyProvider = { requested -> if (requested == chatId) chatKey else null },
            )
        return RuntimeGraph(
            engine = engine,
            store = store,
            envelope = envelope,
            config = SyncTestSupport.config(webDavServer),
            chatId = chatId,
            communityName = "Community",
            chatKey = chatKey,
            identity = identity,
            senderIdentifier = Hex.encode(identity.copySignPublic()),
            communityId = communityId,
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
            assertEquals(1, disk.fileNames(ChatPaths.logDir(chatId)).size)
        }

    @Test
    fun uncertain_put_retry_reuses_original_envelope_and_message_id() =
        runTest {
            val store = store()
            val graph = graph(store)
            val service = MessageSendService(graph, ioDispatcher = Dispatchers.Unconfined, clock = { 1_717_000_000_000L })
            disk.failPutAfterStoreUnderPrefix[ChatPaths.LOG] = 503

            val firstAttempt = service.send("retry me")
            val original = store.messagesForChat(chatId).single()
            val originalEnvelope = original.outboxEnvelope
            assertTrue(originalEnvelope != null)
            assertEquals(MessageEntity.STATUS_FAILED, original.sendStatus)
            assertEquals(1, disk.fileNames(ChatPaths.logDir(chatId)).size)
            val originalLogPath = ChatPaths.message(chatId, original.orderToken, originalEnvelope!!)
            assertArrayEquals(originalEnvelope, disk.fileBytes(originalLogPath))

            disk.failPutAfterStoreUnderPrefix.clear()
            assertTrue(service.retry(firstAttempt.messageId))

            val retried = store.messagesForChat(chatId)
            assertEquals(1, retried.size)
            assertEquals(firstAttempt.messageId, retried.single().messageId)
            assertEquals(MessageEntity.STATUS_SENT, retried.single().sendStatus)
            assertEquals(null, retried.single().outboxEnvelope)
            assertEquals(1, disk.fileNames(ChatPaths.logDir(chatId)).size)
            assertArrayEquals(originalEnvelope, disk.fileBytes(originalLogPath))
        }

    @Test
    fun outgoing_claim_is_exclusive_and_late_failure_cannot_downgrade_read_success() =
        runTest {
            val store = store()
            val graph = graph(store, communityId = "community-a")
            disk.failPutUnderPrefix[ChatPaths.LOG] = 503
            val sent = MessageSendService(graph, ioDispatcher = Dispatchers.Unconfined, clock = { 1_717_000_000_000L }).send("claim me")
            val pending = store.messagesForChat(chatId).single()

            assertNotNull(store.claimOutgoing(sent.messageId, "community-a"))
            assertNull("a second worker/manual attempt must not own this ID", store.claimOutgoing(sent.messageId, "community-a"))
            store.markSent(sent.messageId, "community-a")
            store.markMessagesReadUpTo(chatId, pending.orderToken)
            store.markFailed(sent.messageId, "community-a")

            val row = store.messagesForChat(chatId).single()
            assertEquals(MessageEntity.STATUS_READ, row.sendStatus)
            assertNull(row.outboxEnvelope)
        }

    @Test
    fun manual_retry_and_worker_cycle_cannot_deliver_one_message_concurrently() =
        runTest {
            val store = store()
            val graph = graph(store, communityId = "community-a")
            val service = MessageSendService(graph, ioDispatcher = Dispatchers.IO, clock = { 1_717_000_000_000L })
            disk.failPutUnderPrefix[ChatPaths.LOG] = 503
            val sent = MessageSendService(graph, ioDispatcher = Dispatchers.Unconfined, clock = { 1_717_000_000_000L }).send("claim once")
            disk.failPutUnderPrefix.clear()

            val putStarted = CountDownLatch(1)
            val releasePut = CountDownLatch(1)
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (request.method == "PUT" && request.path.orEmpty().contains("/${ChatPaths.LOG}/")) {
                            putStarted.countDown()
                            check(releasePut.await(5, TimeUnit.SECONDS)) { "timed out waiting to release retry PUT" }
                        }
                        return disk.dispatch(request)
                    }
                }
            try {
                val manualRetry = async(Dispatchers.IO) { service.retry(sent.messageId) }
                assertTrue(withContext(Dispatchers.IO) { putStarted.await(5, TimeUnit.SECONDS) })
                graph.engine.pollCycle(graph.senderIdentifier, listOf(ChatSubscription(chatId)), "community-a")
                assertTrue(
                    "worker must not issue a duplicate while manual retry owns the row",
                    disk.fileNames(ChatPaths.logDir(chatId)).isEmpty(),
                )
                releasePut.countDown()

                assertTrue(manualRetry.await())
                assertEquals(1, disk.fileNames(ChatPaths.logDir(chatId)).size)
                assertEquals(MessageEntity.STATUS_SENT, store.messagesForChat(chatId).single().sendStatus)
            } finally {
                releasePut.countDown()
            }
        }

    @Test
    fun outbox_retry_is_owned_by_community_when_dm_chat_ids_match() =
        runTest {
            val diskB = FakeDisk()
            val serverB =
                MockWebServer().apply {
                    dispatcher = diskB
                    start()
                }
            try {
                val store = store()
                val graphA = graph(store, server, "community-a")
                val graphB = graph(store, serverB, "community-b")
                val serviceA = MessageSendService(graphA, ioDispatcher = Dispatchers.Unconfined, clock = { 1_717_000_000_000L })
                val serviceB = MessageSendService(graphB, ioDispatcher = Dispatchers.Unconfined, clock = { 1_717_000_000_000L })
                disk.failPutUnderPrefix[ChatPaths.LOG] = 503

                val send = serviceA.send("private to A")
                val original = store.messagesForChat(chatId).single()
                assertEquals("community-a", original.outboxCommunityId)
                assertFalse(serviceB.retry(send.messageId))
                graphB.engine.pollCycle(graphB.senderIdentifier, listOf(ChatSubscription(chatId)), "community-b")

                assertTrue(diskB.fileNames(ChatPaths.LOG).isEmpty())
                assertEquals(MessageEntity.STATUS_FAILED, store.messagesForChat(chatId).single().sendStatus)
                disk.failPutUnderPrefix.clear()
                assertTrue(serviceA.retry(send.messageId))
                assertEquals(MessageEntity.STATUS_SENT, store.messagesForChat(chatId).single().sendStatus)
            } finally {
                serverB.shutdown()
            }
        }

    @Test
    fun next_sync_automatically_retries_the_original_outbox_operation() =
        runTest {
            val store = store()
            val graph = graph(store)
            val service = MessageSendService(graph, ioDispatcher = Dispatchers.Unconfined, clock = { 1_717_000_000_000L })
            disk.failPutAfterStoreUnderPrefix[ChatPaths.LOG] = 503
            val sent = service.send("automatic retry")
            assertEquals(MessageEntity.STATUS_FAILED, store.messagesForChat(chatId).single().sendStatus)

            disk.failPutAfterStoreUnderPrefix.clear()
            graph.engine.pollCycle(graph.senderIdentifier, listOf(ChatSubscription(chatId)))

            val rows = store.messagesForChat(chatId)
            assertEquals(1, rows.size)
            assertEquals(sent.messageId, rows.single().messageId)
            assertEquals(MessageEntity.STATUS_SENT, rows.single().sendStatus)
            assertEquals(1, disk.fileNames(ChatPaths.logDir(chatId)).size)
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
