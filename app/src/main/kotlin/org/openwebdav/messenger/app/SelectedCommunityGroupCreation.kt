package org.openwebdav.messenger.app

import org.openwebdav.messenger.keystore.StoredConnection

internal data class SelectedCommunityGroupContext(
    val communityId: String,
    val stored: StoredConnection,
    val graph: RuntimeGraph,
)

/** Resolve, create, and open a group using one community-scoped runtime context. */
internal suspend fun <Context> createGroupInSelectedCommunity(
    communityId: String,
    activeCommunityId: String,
    activateCommunity: suspend (String) -> Boolean,
    resolveContext: suspend (String) -> Context?,
    create: suspend (Context) -> String?,
    open: suspend (Context, String) -> Boolean,
): String? {
    if (communityId != activeCommunityId && !activateCommunity(communityId)) return null
    val context = resolveContext(communityId) ?: return null
    val chatId = create(context) ?: return null
    return chatId.takeIf { open(context, it) }
}
