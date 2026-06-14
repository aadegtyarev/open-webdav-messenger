package org.openwebdav.messenger.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import org.openwebdav.messenger.app.AppContainer
import org.openwebdav.messenger.ui.feed.ChatFeedViewModel

/**
 * Builds the [ChatFeedViewModel] from the already-joined runtime graph (`ui-chat-surface` arch note Choice
 * 1/4). The graph is present whenever the feed is shown — the nav only routes to the feed after a create /
 * join produced it, or at relaunch when a persisted config rebuilt it.
 */
internal object FeedViewModelFactory : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(ChatFeedViewModel::class.java)) { "Unknown ViewModel: $modelClass" }
        val graph = AppContainer.runtimeGraph() ?: error("no joined chat — feed shown without a runtime graph")
        return ChatFeedViewModel(graph) as T
    }
}
