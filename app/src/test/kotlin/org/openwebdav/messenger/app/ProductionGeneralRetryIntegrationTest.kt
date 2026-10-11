package org.openwebdav.messenger.app

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.openwebdav.messenger.data.MessageEntity
import org.openwebdav.messenger.protocol.ChatPaths
import org.openwebdav.messenger.ui.FeedViewModelFactory
import org.openwebdav.messenger.ui.feed.ChatFeedViewModel
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ProductionGeneralRetryIntegrationTest {
    @Test
    fun failed_general_envelope_reaches_webdav_via_production_feed_view_model_retry() =
        runTest {
            val fixture = ProductionGeneralRetryFixture(testScheduler)
            try {
                fixture.start()
                val general = checkNotNull(AppContainer.runtimeGraph())
                val service = MessageSendService(general, Dispatchers.Unconfined, clock = { 1_717_000_000_000L })
                fixture.disk.failPutAfterStoreUnderPrefix[ChatPaths.LOG] = 503
                val result = service.send("retry persisted General envelope")
                val failed = fixture.store.messagesForChat(fixture.chatId).single()
                val original = failed.outboxEnvelope
                assertFalse(result.complete)
                assertEquals(MessageEntity.STATUS_FAILED, failed.sendStatus)
                assertTrue(original != null)
                fixture.disk.failPutAfterStoreUnderPrefix.clear()
                assertTrue(AppContainer.isExactUndeletableGeneralAnchor(fixture.communityId, fixture.chatId))
                assertTrue(AppContainer.runtimeGraph() === general)
                assertEquals(fixture.communityId, general.communityId)
                assertEquals(fixture.chatId, general.chatId)
                assertEquals(
                    TombstoneReconciler.Result.NoTombstone,
                    AppContainer.reconcileRemoteTombstone(fixture.communityId, fixture.chatId, expectedGraph = general),
                )
                fixture.disk.failPutUnderPrefix[ChatPaths.LOG] = 503
                assertFalse(service.retry(failed.messageId))
                fixture.disk.failPutUnderPrefix.clear()

                val viewModels = ViewModelStore()
                try {
                    viewModels.put("feed", FeedViewModelFactory.create(ChatFeedViewModel::class.java))
                    assertTrue(AppContainer.runtimeGraph() === general)
                    (viewModels.get("feed") as ChatFeedViewModel).retryFailed(failed.messageId)
                    advanceUntilIdle()
                    withContext(Dispatchers.IO) {
                        withTimeout(5_000) {
                            while (fixture.store.messagesForChat(fixture.chatId).single().sendStatus != MessageEntity.STATUS_SENT) {
                                delay(10)
                            }
                        }
                    }
                } finally {
                    viewModels.clear()
                    advanceUntilIdle()
                }
                assertEquals(MessageEntity.STATUS_SENT, fixture.store.messagesForChat(fixture.chatId).single().sendStatus)
                assertEquals(
                    original!!.toList(),
                    fixture.disk.fileBytes(ChatPaths.message(fixture.chatId, failed.orderToken, original))?.toList(),
                )
                verifyOtherGeneralRetry(fixture)
            } finally {
                fixture.close()
            }
        }
}
