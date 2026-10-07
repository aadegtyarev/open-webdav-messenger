package org.openwebdav.messenger.ui

import org.openwebdav.messenger.app.AppContainer

/** Distinguishes ViewModel instances by their community, chat, and concrete runtime graph. */
internal fun runtimeScopeKey(screen: String): String {
    val graph = AppContainer.runtimeGraph()
    return "$screen:${AppContainer.activeCommunityId}:${graph?.chatId}:${graph?.scopeKey}"
}
