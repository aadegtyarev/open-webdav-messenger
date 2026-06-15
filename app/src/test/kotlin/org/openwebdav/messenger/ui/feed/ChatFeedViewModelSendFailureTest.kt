package org.openwebdav.messenger.ui.feed

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.openwebdav.messenger.app.AppTestSupport
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
 * the error is surfaced and the message stays in chat with FAILED status. The draft is NOT
 * restored — the message remains visible as a failed bubble the user can retry.
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

    /** A seal that throws during send surfaces the error; draft is NOT restored (message stays as failed bubble). */
    @Test
    fun send_failure_restores_draft_and_surfaces_error() =
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

            // Draft is cleared (optimistic) — message is in chat with SENDING status.
            // Error is surfaced.
            assertEquals("", vm.draft.first())
            assertEquals(ChatFeedViewModel.SEND_FAILED_MESSAGE, vm.sendError.first())
        }
}
