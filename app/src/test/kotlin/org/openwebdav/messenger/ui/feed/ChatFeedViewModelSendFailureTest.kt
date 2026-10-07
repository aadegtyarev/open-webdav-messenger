package org.openwebdav.messenger.ui.feed

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.openwebdav.messenger.app.AppTestSupport
import org.openwebdav.messenger.app.ChatMessageSender
import org.openwebdav.messenger.app.MessageSendService
import org.openwebdav.messenger.app.RuntimeGraph
import org.openwebdav.messenger.crypto.Aead
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.crypto.MessageCrypto
import org.openwebdav.messenger.data.MessageStore
import org.openwebdav.messenger.data.MessengerDatabase
import org.openwebdav.messenger.directory.SealFailingNative
import org.openwebdav.messenger.identity.Identity
import org.openwebdav.messenger.message.MessageEnvelope
import org.openwebdav.messenger.protocol.Hex
import org.openwebdav.messenger.sync.SyncEngine
import org.openwebdav.messenger.sync.SyncTestSupport
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Send-failure handling for [ChatFeedViewModel]: if seal throws or the disk write fails,
 * the error is surfaced; when local persistence has not succeeded, the draft remains recoverable.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ChatFeedViewModelSendFailureTest {
    private val mainDispatcher = StandardTestDispatcher()
    private lateinit var server: MockWebServer
    private lateinit var db: MessengerDatabase
    private lateinit var store: MessageStore
    private lateinit var identity: Identity
    private val chatId = SyncTestSupport.CHAT_ID
    private val chatKey: ChatKey = SyncTestSupport.fixedChatKey()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        server = MockWebServer()
        server.start()
        db = SyncTestSupport.inMemoryDb()
        store = SyncTestSupport.store(db)
        identity = AppTestSupport.newIdentity()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        server.shutdown()
        db.close()
    }

    private fun testGraph(): RuntimeGraph {
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

    private class ControlledSender(
        private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher,
        private val persistenceGate: CompletableDeferred<Unit>? = null,
        private val resultGate: CompletableDeferred<Unit>? = null,
        private val failFirst: Boolean = false,
        private val resultComplete: Boolean = true,
    ) : ChatMessageSender {
        var attempts = 0
        var persisted = 0

        override suspend fun send(
            text: String,
            onRecoverablyPersisted: suspend () -> Unit,
        ): MessageSendService.SendResult {
            attempts++
            withContext(ioDispatcher) {
                if (failFirst && attempts == 1) error("local persistence failed")
                persistenceGate?.await()
            }
            persisted++
            onRecoverablyPersisted()
            withContext(ioDispatcher) { resultGate?.await() }
            return MessageSendService.SendResult("test-message-id", resultComplete)
        }

        override suspend fun retry(messageId: String): Boolean = true
    }

    @Test
    fun late_network_failure_does_not_surface_over_a_newer_draft() =
        runTest(mainDispatcher) {
            val persistenceGate = CompletableDeferred<Unit>()
            val networkGate = CompletableDeferred<Unit>()
            val sender =
                ControlledSender(
                    StandardTestDispatcher(testScheduler),
                    persistenceGate = persistenceGate,
                    resultGate = networkGate,
                    resultComplete = false,
                )
            val vm = ChatFeedViewModel(testGraph(), sender)
            vm.onDraft("original")
            vm.send()
            runCurrent()

            persistenceGate.complete(Unit)
            runCurrent()
            assertEquals("", vm.draft.first())
            vm.onDraft("newer draft")
            networkGate.complete(Unit)
            advanceUntilIdle()

            assertEquals("newer draft", vm.draft.first())
            assertEquals(null, vm.sendError.first())
        }

    @Test
    fun duplicate_tap_reserves_revision_and_stale_persistence_keeps_new_draft() =
        runTest(mainDispatcher) {
            val gate = CompletableDeferred<Unit>()
            val sender = ControlledSender(StandardTestDispatcher(testScheduler), gate)
            val vm = ChatFeedViewModel(testGraph(), sender)

            vm.onDraft("original")
            vm.send()
            runCurrent()
            vm.send()
            runCurrent()
            assertEquals(1, sender.attempts)

            vm.onDraft("newer")
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(1, sender.persisted)
            assertEquals("newer", vm.draft.first())
            assertEquals(null, vm.sendError.first())

            vm.send()
            advanceUntilIdle()
            assertEquals(2, sender.attempts)
            assertEquals("", vm.draft.first())
        }

    @Test
    fun persistence_failure_releases_revision_reservation_without_clearing_draft() =
        runTest(mainDispatcher) {
            val sender = ControlledSender(StandardTestDispatcher(testScheduler), failFirst = true)
            val vm = ChatFeedViewModel(testGraph(), sender)
            vm.onDraft("retry after persistence failure")

            vm.send()
            advanceUntilIdle()
            assertEquals("retry after persistence failure", vm.draft.first())
            assertEquals(1, sender.attempts)

            vm.send()
            advanceUntilIdle()
            assertEquals(2, sender.attempts)
            assertEquals(1, sender.persisted)
            assertEquals("", vm.draft.first())
        }

    /** A seal failure before local persistence keeps the draft and surfaces the error. */
    @Test
    fun send_failure_before_persistence_keeps_draft_and_surfaces_error() =
        runTest(mainDispatcher) {
            // The envelope seals via a native that always fails the AEAD encrypt → send() throws at seal.
            val failingEnvelope =
                MessageEnvelope.create(
                    MessageCrypto(Aead(SealFailingNative(AppTestSupport.native()))),
                    AppTestSupport.identityCrypto(),
                )
            val engine =
                SyncEngine(
                    transport = SyncTestSupport.transport(server),
                    envelope = failingEnvelope,
                    store = store,
                    keyProvider = { chatKey },
                )
            val graph =
                RuntimeGraph(
                    engine = engine,
                    store = store,
                    envelope = failingEnvelope,
                    config = SyncTestSupport.config(server),
                    chatId = chatId,
                    communityName = "Community",
                    chatKey = chatKey,
                    identity = identity,
                    senderIdentifier = Hex.encode(identity.copySignPublic()),
                )
            // Drive MessageSendService on the test dispatcher so the seal-throw resolves deterministically.
            val vm = ChatFeedViewModel(graph, MessageSendService(graph, ioDispatcher = mainDispatcher))

            vm.onDraft("don't lose me")
            vm.send()
            advanceUntilIdle()

            // The envelope failed before a recoverable Room echo could be stored.
            assertEquals("don't lose me", vm.draft.first())
            assertEquals(ChatFeedViewModel.SEND_FAILED_MESSAGE, vm.sendError.first())

            vm.onDraft("submitted draft")
            vm.send()
            vm.onDraft("newer draft")
            advanceUntilIdle()
            assertEquals("newer draft", vm.draft.first())
        }
}
