package org.openwebdav.messenger.app

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.openwebdav.messenger.data.MessageEntity
import org.openwebdav.messenger.protocol.ChatPaths
import org.openwebdav.messenger.ui.FeedViewModelFactory
import org.openwebdav.messenger.ui.feed.ChatFeedViewModel

@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun TestScope.verifyOtherGeneralRetry(fixture: ProductionGeneralRetryFixture) {
    fixture.installOtherGeneral()
    val graph = checkNotNull(AppContainer.runtimeGraph())
    assertTrue(AppContainer.runtimeGraph() === graph)
    assertEquals(fixture.communityId, graph.communityId)
    assertEquals(fixture.otherChatId, graph.chatId)
    assertEquals("general", AppContainer.chatFor(fixture.communityId, fixture.otherChatId)?.kind)
    assertTrue(AppContainer.isExactUndeletableGeneralAnchor(fixture.communityId, fixture.chatId))
    assertFalse(AppContainer.isExactUndeletableGeneralAnchor(fixture.communityId, fixture.otherChatId))
    val service = MessageSendService(graph, Dispatchers.Unconfined)
    fixture.disk.failPutUnderPrefix[ChatPaths.LOG] = 503
    assertFalse(service.send("unauthorized envelope").complete)
    val failed = fixture.store.messagesForChat(fixture.otherChatId).single()
    fixture.disk.failPutUnderPrefix.clear()
    assertEquals(
        TombstoneReconciler.Result.Unauthorized,
        AppContainer.reconcileRemoteTombstone(fixture.communityId, fixture.otherChatId),
    )
    val viewModels = ViewModelStore()
    try {
        viewModels.put("other", FeedViewModelFactory.create(ChatFeedViewModel::class.java))
        (viewModels.get("other") as ChatFeedViewModel).retryFailed(failed.messageId)
        advanceUntilIdle()
    } finally {
        viewModels.clear()
        advanceUntilIdle()
    }
    assertEquals(MessageEntity.STATUS_FAILED, fixture.store.messagesForChat(fixture.otherChatId).single().sendStatus)
    assertTrue(fixture.disk.fileNames(ChatPaths.logDir(fixture.otherChatId)).isEmpty())
}
