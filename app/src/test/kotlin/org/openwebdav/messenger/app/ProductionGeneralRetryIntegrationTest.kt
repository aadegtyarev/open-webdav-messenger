package org.openwebdav.messenger.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.openwebdav.messenger.data.MessageEntity
import org.openwebdav.messenger.data.MessengerDatabase
import org.openwebdav.messenger.protocol.ChatPaths
import org.openwebdav.messenger.sync.FakeDisk
import org.openwebdav.messenger.sync.SyncTestSupport
import org.openwebdav.messenger.ui.feed.ChatFeedViewModel
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ProductionGeneralRetryIntegrationTest {
    private val server = MockWebServer()
    private val disk = FakeDisk()
    private val db: MessengerDatabase = SyncTestSupport.inMemoryDb()
    private val identity = AppTestSupport.newIdentity()
    private val chatKey = SyncTestSupport.fixedChatKey()
    private val chatId = SyncTestSupport.CHAT_ID

    @org.junit.Before
    fun setUp() {
        server.dispatcher = disk
        server.start()
    }

    @After
    fun tearDown() {
        AppContainer.clearChatOpenTestSeam()
        server.shutdown()
        db.close()
    }

    @Test
    fun production_general_retry_retries_failed_envelope_and_blocks_other_general() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val context = RuntimeEnvironment.getApplication()
            val communityId = "general-retry-${UUID.randomUUID().toString().take(8)}"
            val other = "other-general"
            val config = SyncTestSupport.config(server)
            val store = SyncTestSupport.store(db, communityId)
            AppContainer.bind(context)
            AppTestSupport.configureGeneralRetryStorage(context, communityId, chatId, other, config, chatKey)
            try {
                disk.failPutAfterStoreUnderPrefix[ChatPaths.LOG] = 503
                val general = AppTestSupport.outgoingTestGraph(server, store, identity, chatKey, chatId, communityId)
                val service = MessageSendService(general, Dispatchers.Unconfined, clock = { 1_717_000_000_000L })
                val result = service.send("retry persisted General envelope")
                val failed = store.messagesForChat(chatId).single()
                val original = failed.outboxEnvelope
                assertFalse(result.complete)
                assertEquals(MessageEntity.STATUS_FAILED, failed.sendStatus)
                assertTrue(original != null)
                disk.failPutAfterStoreUnderPrefix.clear()
                assertTrue(AppContainer.isExactUndeletableGeneralAnchor(communityId, chatId))
                assertEquals(TombstoneReconciler.Result.NoTombstone, AppContainer.reconcileRemoteTombstone(communityId, chatId))
                assertTrue(service.retry(failed.messageId))
                assertEquals(MessageEntity.STATUS_SENT, store.messagesForChat(chatId).single().sendStatus)
                assertEquals(original!!.toList(), disk.fileBytes(ChatPaths.message(chatId, failed.orderToken, original))?.toList())
                disk.failPutUnderPrefix[ChatPaths.LOG] = 503
                val otherGraph = AppTestSupport.outgoingTestGraph(server, store, identity, chatKey, other, communityId)
                val otherService = MessageSendService(otherGraph, Dispatchers.Unconfined)
                assertFalse(otherService.send("unauthorized envelope").complete)
                val blocked = store.messagesForChat(other).single()
                disk.failPutUnderPrefix.clear()
                ChatFeedViewModel(otherGraph, otherService).retryFailed(blocked.messageId)
                advanceUntilIdle()
                assertEquals(MessageEntity.STATUS_FAILED, store.messagesForChat(other).single().sendStatus)
                assertTrue(disk.fileNames(ChatPaths.logDir(other)).isEmpty())
            } finally {
                AppContainer.clearChatOpenTestSeam()
                Dispatchers.resetMain()
            }
        }
}
