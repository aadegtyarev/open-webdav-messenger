package org.openwebdav.messenger.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
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
import org.openwebdav.messenger.account.AccountMutationBarrier
import org.openwebdav.messenger.crypto.Aead
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.crypto.MessageCrypto
import org.openwebdav.messenger.data.ChatDeletionEntity
import org.openwebdav.messenger.data.MessageEntity
import org.openwebdav.messenger.data.MessageStore
import org.openwebdav.messenger.data.MessengerDatabase
import org.openwebdav.messenger.directory.SealFailingNative
import org.openwebdav.messenger.identity.Identity
import org.openwebdav.messenger.message.MessageEnvelope
import org.openwebdav.messenger.protocol.ChatPaths
import org.openwebdav.messenger.protocol.Hex
import org.openwebdav.messenger.protocol.MessageId
import org.openwebdav.messenger.sync.ChatSubscription
import org.openwebdav.messenger.sync.FakeDisk
import org.openwebdav.messenger.sync.OutgoingOutbox
import org.openwebdav.messenger.sync.SendOutcome
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

    private fun store(communityId: String = "default"): MessageStore = SyncTestSupport.store(db, communityId)

    private fun graph(
        store: MessageStore,
        webDavServer: MockWebServer = server,
        communityId: String = "default",
        envelope: MessageEnvelope =
            MessageEnvelope.create(
                MessageCrypto(Aead(AppTestSupport.native())),
                AppTestSupport.identityCrypto(),
            ),
    ): RuntimeGraph {
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
    fun self_send_echo_and_status_survive_room_database_restart() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val name = "outbox-restart-${java.util.UUID.randomUUID()}"
            val persistentDb = Room.databaseBuilder(context, MessengerDatabase::class.java, name).build()
            try {
                val persistentStore = SyncTestSupport.store(persistentDb)
                val service = MessageSendService(graph(persistentStore), ioDispatcher = Dispatchers.Unconfined)
                val sent = service.send("survive restart")
                assertTrue(sent.logWritten)
                assertEquals(1, persistentStore.messagesForChat(chatId).size)
                persistentDb.close()

                val reopened = Room.databaseBuilder(context, MessengerDatabase::class.java, name).build()
                try {
                    val reopenedStore = SyncTestSupport.store(reopened)
                    val rows = reopenedStore.messagesForChat(chatId)
                    assertEquals(1, rows.size)
                    assertEquals(sent.messageId, rows.single().messageId)
                    assertEquals("survive restart", rows.single().body)
                    assertEquals(MessageEntity.STATUS_SENT, rows.single().sendStatus)
                    assertNull(rows.single().outboxEnvelope)
                    assertEquals(1, disk.fileNames(ChatPaths.logDir(chatId)).size)
                } finally {
                    reopened.close()
                }
            } finally {
                runCatching { persistentDb.close() }
                context.deleteDatabase(name)
            }
        }

    @Test
    fun seal_failure_does_not_persist_or_publish_an_outgoing_message() =
        runTest {
            val store = store()
            val failingEnvelope =
                MessageEnvelope.create(
                    MessageCrypto(Aead(SealFailingNative(AppTestSupport.native()))),
                    AppTestSupport.identityCrypto(),
                )
            val service = MessageSendService(graph(store, envelope = failingEnvelope), ioDispatcher = Dispatchers.Unconfined)

            val failure = runCatching { service.send("seal must fail") }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertTrue(store.messagesForChat(chatId).isEmpty())
            assertTrue(disk.fileNames(ChatPaths.logDir(chatId)).isEmpty())
        }

    @Test
    fun send_requires_verified_roster_and_persists_the_same_recipient_snapshot() =
        runTest {
            val store = store("community-a")
            val graph = graph(store, communityId = "community-a")
            graph.updateRecipientReadiness(RecipientReadiness.Loading)
            val service = MessageSendService(graph, ioDispatcher = Dispatchers.Unconfined)

            val pendingFailure = runCatching { service.send("must wait") }.exceptionOrNull()
            assertTrue(pendingFailure is IllegalStateException)
            assertTrue(store.messagesForChat(chatId).isEmpty())

            val peer = "0123456789abcdef"
            graph.updateRecipientReadiness(RecipientReadiness.Ready(listOf(peer)))
            disk.failPutAfterStoreUnderPrefix[ChatPaths.LOG] = 503
            assertFalse(service.send("verified snapshot").complete)
            val pending = store.messagesForChat(chatId).single()
            assertEquals(peer, pending.outboxRecipients)
            graph.updateRecipientReadiness(RecipientReadiness.Unavailable("roster changed"))
            assertEquals(peer, pending.outboxRecipients)
        }

    @Test
    fun later_send_after_verified_member_joins_uses_exact_new_roster() =
        runTest {
            val store = store()
            val graph = graph(store)
            val service = MessageSendService(graph, ioDispatcher = Dispatchers.Unconfined)
            val first = service.send("self only")
            assertTrue(first.complete)
            assertTrue(disk.fileNames(ChatPaths.CHANGES).isEmpty())

            val peerA = Hex.encode(ByteArray(32) { 0x11 })
            val peerB = Hex.encode(ByteArray(32) { 0x22 })
            graph.updateRecipientReadiness(RecipientReadiness.Ready(listOf(peerA, peerB)))
            disk.failPutUnderPrefix[ChatPaths.LOG] = 503
            val second = service.send("after peer join")
            assertFalse(second.complete)
            val queued = store.messagesForChat(chatId).last()
            assertEquals("$peerA\n$peerB", queued.outboxRecipients)
            disk.failPutUnderPrefix.clear()
            assertTrue(service.retry(second.messageId))

            val rows = store.messagesForChat(chatId)
            assertEquals(2, rows.size)
            assertEquals(MessageEntity.STATUS_SENT, rows.last().sendStatus)
            assertEquals(1, disk.fileNames(ChatPaths.changeIndex(peerA, chatId)).size)
            assertEquals(1, disk.fileNames(ChatPaths.changeIndex(peerB, chatId)).size)
            assertEquals(2, disk.fileNames(ChatPaths.logDir(chatId)).size)
        }

    @Test
    fun verified_peer_change_index_polls_the_shared_log_append() =
        runTest {
            val senderStore = store()
            val senderGraph = graph(senderStore)
            val peer = AppTestSupport.newIdentity()
            val peerIdentifier = Hex.encode(peer.copySignPublic())
            senderGraph.updateRecipientReadiness(RecipientReadiness.Ready(listOf(peerIdentifier)))
            val sent = MessageSendService(senderGraph, ioDispatcher = Dispatchers.Unconfined).send("poll peer path")
            assertTrue(sent.logWritten)
            assertEquals(1, disk.fileNames(ChatPaths.changeIndex(peerIdentifier, chatId)).size)

            val peerStore = store("peer-community")
            val peerEngine =
                SyncEngine(
                    transport = transport(),
                    envelope = SyncTestSupport.messageEnvelope(),
                    store = peerStore,
                    keyProvider = { requested -> if (requested == chatId) chatKey else null },
                )
            val polled = peerEngine.pollCycle(peerIdentifier, listOf(ChatSubscription(chatId)), "peer-community")
            assertEquals(1, polled.newCount)
            val received = peerStore.messagesForChat(chatId)
            assertEquals(1, received.size)
            assertEquals("poll peer path", received.single().body)
            assertEquals(sent.messageId, received.single().messageId)
        }

    @Test
    fun successful_log_append_is_sent_while_failed_member_notification_remains_retryable() =
        runTest {
            val store = store()
            val graph = graph(store)
            val peer = Hex.encode(ByteArray(32) { 0x33 })
            graph.updateRecipientReadiness(RecipientReadiness.Ready(listOf(peer)))
            disk.failPutUnderPrefix[ChatPaths.changeIndex(peer, chatId)] = 503
            val service = MessageSendService(graph, ioDispatcher = Dispatchers.Unconfined)

            val result = service.send("append succeeded, notification pending")
            val sent = store.messagesForChat(chatId).single()
            assertTrue(result.logWritten)
            assertTrue(result.complete)
            assertEquals(MessageEntity.STATUS_SENT, sent.sendStatus)
            assertNotNull(sent.outboxEnvelope)
            assertEquals(listOf(sent.messageId), store.pendingOutgoing("default").map { it.messageId })
            assertTrue(disk.fileNames(ChatPaths.logDir(chatId)).size == 1)

            disk.failPutUnderPrefix.clear()
            graph.engine.pollCycle(graph.senderIdentifier, listOf(ChatSubscription(chatId)))
            val completed = store.messagesForChat(chatId).single()
            assertEquals(MessageEntity.STATUS_SENT, completed.sendStatus)
            assertNull(completed.outboxEnvelope)
            assertEquals(1, disk.fileNames(ChatPaths.logDir(chatId)).size)
            assertEquals(1, disk.fileNames(ChatPaths.changeIndex(peer, chatId)).size)
        }

    @Test
    fun send_for_replaced_account_is_rejected_before_persistence_or_webdav() =
        runTest {
            val store = store()
            val service = MessageSendService(graph(store), ioDispatcher = Dispatchers.Unconfined, isCurrent = { false })

            val failure = runCatching { service.send("stale account") }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertTrue(store.messagesForChat(chatId).isEmpty())
            assertTrue(disk.fileNames(ChatPaths.logDir(chatId)).isEmpty())
        }

    @Test
    fun send_serializes_with_graveyard_commit_and_never_writes_after_it() =
        runTest {
            val store = store("community-a")
            val graph = graph(store, communityId = "community-a")
            val service = MessageSendService(graph, ioDispatcher = Dispatchers.IO)
            val putStarted = CountDownLatch(1)
            val releasePut = CountDownLatch(1)
            val events = java.util.Collections.synchronizedList(mutableListOf<String>())
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (request.method == "PUT" && request.path.orEmpty().contains("/${ChatPaths.LOG}/")) {
                            putStarted.countDown()
                            check(releasePut.await(5, TimeUnit.SECONDS))
                            val response = disk.dispatch(request)
                            events += "put-complete"
                            return response
                        }
                        return disk.dispatch(request)
                    }
                }
            val sending = async(Dispatchers.IO) { service.send("before deletion") }
            assertTrue(withContext(Dispatchers.IO) { putStarted.await(5, TimeUnit.SECONDS) })
            val commitAttempting = CompletableDeferred<Unit>()
            val deletion =
                async(Dispatchers.IO) {
                    commitAttempting.complete(Unit)
                    AccountMutationBarrier.process.withExclusive {
                        val result = store.commitChatDeletion(deletionRow("community-a"))
                        events += "graveyard-committed"
                        result
                    }
                }
            commitAttempting.await()
            assertFalse("graveyard waits behind the active send operation", deletion.isCompleted)
            releasePut.countDown()
            sending.await()
            deletion.await()
            assertTrue(events.indexOf("put-complete") < events.indexOf("graveyard-committed"))
            assertTrue(store.isChatDeleted(chatId))
        }

    @Test
    fun committed_graveyard_rejects_new_sends_before_room_or_webdav() =
        runTest {
            val store = store("community-a")
            store.commitChatDeletion(deletionRow("community-a"))
            val graph = graph(store, communityId = "community-a")
            val service = MessageSendService(graph, ioDispatcher = Dispatchers.Unconfined)

            assertTrue(runCatching { service.send("deleted chat") }.exceptionOrNull() is IllegalStateException)
            assertTrue(store.messagesForChat(chatId).isEmpty())
            assertTrue(disk.fileNames(ChatPaths.logDir(chatId)).isEmpty())
            assertFalse(service.retry("missing-message"))
        }

    private fun deletionRow(communityId: String) =
        ChatDeletionEntity(
            communityId = communityId,
            chatId = chatId,
            scope = ChatDeletionEntity.SCOPE_GROUP,
            originHash = "a".repeat(32),
            tombstoneHash = "b".repeat(32),
            tombstoneStoredBytes = byteArrayOf(1),
            phase = ChatDeletionEntity.PHASE_LOCAL_COMMITTED,
            updatedAtMillis = 1L,
        )

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
            val store = store("community-a")
            val graph = graph(store, communityId = "community-a")
            disk.failPutUnderPrefix[ChatPaths.LOG] = 503
            val sent = MessageSendService(graph, ioDispatcher = Dispatchers.Unconfined, clock = { 1_717_000_000_000L }).send("claim me")
            val pending = store.messagesForChat(chatId).single()

            assertNotNull(store.claimOutgoing(sent.messageId, "community-a", "claim-a"))
            assertNull("a second worker/manual attempt must not own this ID", store.claimOutgoing(sent.messageId, "community-a", "claim-b"))
            store.finishOutgoingClaim(sent.messageId, "community-a", "claim-a")
            store.markMessagesReadUpTo(chatId, pending.orderToken)
            store.markFailed(sent.messageId, "community-a")

            val row = store.messagesForChat(chatId).single()
            assertEquals(MessageEntity.STATUS_READ, row.sendStatus)
            assertNull(row.outboxEnvelope)
        }

    @Test
    fun contender_cleanup_and_late_owner_cleanup_cannot_release_another_claim() =
        runTest {
            val store = store("community-a")
            store.persist(
                messageId = "owned-claim",
                orderToken = "0005",
                message = SyncTestSupport.text(identity, "retry"),
                receivedAtMillis = 1L,
                sendStatus = MessageEntity.STATUS_FAILED,
                outboxEnvelope = byteArrayOf(1),
                outboxRecipients = listOf("peer"),
                outboxCommunityId = "community-a",
            )

            assertNotNull(store.claimOutgoing("owned-claim", "community-a", "attempt-a"))
            assertNull(
                "B cannot acquire A's active claim",
                store.claimOutgoing("owned-claim", "community-a", "attempt-b"),
            )
            store.failOutgoingClaim("owned-claim", "community-a", "attempt-b") // cancelled during acquisition
            assertNull(
                "C remains blocked while A owns the row",
                store.claimOutgoing("owned-claim", "community-a", "attempt-c"),
            )

            store.failOutgoingClaim("owned-claim", "community-a", "attempt-a")
            assertNotNull(store.claimOutgoing("owned-claim", "community-a", "attempt-c"))
            store.failOutgoingClaim("owned-claim", "community-a", "attempt-a") // late cleanup from A
            assertEquals(MessageEntity.STATUS_SENDING, store.messagesForChat(chatId).single().sendStatus)
            store.failOutgoingClaim("owned-claim", "community-a", "attempt-c")
            assertEquals(MessageEntity.STATUS_FAILED, store.messagesForChat(chatId).single().sendStatus)
        }

    @Test
    fun late_initial_failure_cannot_release_newer_retry_claim_or_start_parallel_delivery() =
        runTest {
            val store = store("community-a")
            store.persist(
                messageId = "late-initial-failure",
                orderToken = "0005",
                message = SyncTestSupport.text(identity, "retry"),
                receivedAtMillis = 1L,
                sendStatus = MessageEntity.STATUS_SENDING,
                outboxEnvelope = byteArrayOf(1),
                outboxRecipients = listOf("peer"),
                outboxCommunityId = "community-a",
            )
            store.markFailed("late-initial-failure", "community-a")
            assertEquals(MessageEntity.STATUS_FAILED, store.messagesForChat(chatId).single().sendStatus)

            var deliveryAttempts = 0
            val deliveryStarted = CompletableDeferred<Unit>()
            val finishDelivery = CompletableDeferred<Unit>()
            val outbox =
                OutgoingOutbox(store) { _, _ ->
                    deliveryAttempts++
                    deliveryStarted.complete(Unit)
                    finishDelivery.await()
                    SendOutcome(true, 1, 0)
                }
            val activeRetry = async { outbox.retry("late-initial-failure", "community-a", "self") }
            deliveryStarted.await()

            store.markFailed("late-initial-failure", "community-a")
            assertFalse(outbox.retry("late-initial-failure", "community-a", "self"))
            assertEquals(1, deliveryAttempts)
            assertEquals(MessageEntity.STATUS_SENDING, store.messagesForChat(chatId).single().sendStatus)

            finishDelivery.complete(Unit)
            assertTrue(activeRetry.await())
            assertEquals(MessageEntity.STATUS_SENT, store.messagesForChat(chatId).single().sendStatus)
        }

    @Test
    fun manual_retry_and_worker_cycle_cannot_deliver_one_message_concurrently() =
        runTest {
            val store = store("community-a")
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
                val storeA = store("community-a")
                val storeB = store("community-b")
                val graphA = graph(storeA, server, "community-a")
                val graphB = graph(storeB, serverB, "community-b")
                val serviceA = MessageSendService(graphA, ioDispatcher = Dispatchers.Unconfined, clock = { 1_717_000_000_000L })
                val serviceB = MessageSendService(graphB, ioDispatcher = Dispatchers.Unconfined, clock = { 1_717_000_000_000L })
                disk.failPutUnderPrefix[ChatPaths.LOG] = 503

                val send = serviceA.send("private to A")
                val original = storeA.messagesForChat(chatId).single()
                assertEquals("community-a", original.outboxCommunityId)
                assertFalse(serviceB.retry(send.messageId))
                graphB.engine.pollCycle(graphB.senderIdentifier, listOf(ChatSubscription(chatId)), "community-b")

                assertTrue(diskB.fileNames(ChatPaths.logDir(chatId)).isEmpty())
                assertEquals(MessageEntity.STATUS_FAILED, storeA.messagesForChat(chatId).single().sendStatus)
                disk.failPutUnderPrefix.clear()
                assertTrue(serviceA.retry(send.messageId))
                assertEquals(MessageEntity.STATUS_SENT, storeA.messagesForChat(chatId).single().sendStatus)
            } finally {
                serverB.shutdown()
            }
        }

    @Test
    fun cancelling_initial_send_after_durable_insert_releases_it_for_same_process_retry() =
        runTest {
            val store = store("community-a")
            val graph = graph(store, communityId = "community-a")
            val service = MessageSendService(graph, ioDispatcher = Dispatchers.Unconfined)
            val persisted = CompletableDeferred<Unit>()
            val sending =
                launch {
                    service.send("cancel after insert") {
                        persisted.complete(Unit)
                        awaitCancellation()
                    }
                }
            persisted.await()
            val messageId = store.messagesForChat(chatId).single().messageId

            sending.cancelAndJoin()

            assertEquals(MessageEntity.STATUS_FAILED, store.messagesForChat(chatId).single().sendStatus)
            assertTrue(service.retry(messageId))
            assertEquals(MessageEntity.STATUS_SENT, store.messagesForChat(chatId).single().sendStatus)
        }

    @Test
    fun cancelling_claimed_outbox_attempt_releases_it_for_retry_without_reinitializing() =
        runTest {
            val store = store("community-a")
            val message = SyncTestSupport.text(identity, "stored retry")
            store.persist(
                "cancel-retry-id",
                "0005",
                message,
                1L,
                sendStatus = MessageEntity.STATUS_FAILED,
                outboxEnvelope = byteArrayOf(1),
                outboxRecipients = listOf("peer"),
                outboxCommunityId = "community-a",
            )
            val started = CompletableDeferred<Unit>()
            val cancelledAttempt =
                OutgoingOutbox(store) { _, _ ->
                    started.complete(Unit)
                    awaitCancellation()
                }
            val retry = launch { cancelledAttempt.retry("cancel-retry-id", "community-a", "self") }
            started.await()
            retry.cancelAndJoin()

            assertEquals(MessageEntity.STATUS_FAILED, store.messagesForChat(chatId).single().sendStatus)
            val sameProcessRetry = OutgoingOutbox(store) { _, _ -> SendOutcome(true, 1, 0) }
            assertTrue(sameProcessRetry.retry("cancel-retry-id", "community-a", "self"))
            assertEquals(MessageEntity.STATUS_SENT, store.messagesForChat(chatId).single().sendStatus)
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
