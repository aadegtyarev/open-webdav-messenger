package org.openwebdav.messenger.app

import org.openwebdav.messenger.account.AccountMutationBarrier
import org.openwebdav.messenger.keystore.StoredConnection

internal data class SelectedCommunityGroupContext(
    val communityId: String,
    val stored: StoredConnection,
    val graph: RuntimeGraph,
    val selectionRevision: Long,
)

internal fun selectedCommunityGroupContext(
    communityId: String,
    activeCommunityId: String,
    stored: StoredConnection?,
    graph: RuntimeGraph?,
    selectionRevision: Long,
): SelectedCommunityGroupContext? {
    if (communityId != activeCommunityId || stored == null || graph == null) return null
    if (graph.communityId != communityId || graph.config != stored.config) return null
    return SelectedCommunityGroupContext(communityId, stored, graph, selectionRevision)
}

/** Resolve, create, and open under one account barrier; activation must use its exclusive path. */
internal suspend fun <Context> createGroupInSelectedCommunity(
    communityId: String,
    activeCommunityId: String,
    isRuntimeCurrent: () -> Boolean = { true },
    activateCommunity: suspend (String) -> Boolean,
    resolveContext: suspend (String) -> Context?,
    create: suspend (Context) -> String?,
    open: suspend (Context, String) -> Boolean,
): String? =
    AccountMutationBarrier.process.withExclusive {
        if (!isRuntimeCurrent()) return@withExclusive null
        if (communityId != activeCommunityId && !activateCommunity(communityId)) return@withExclusive null
        val context = resolveContext(communityId) ?: return@withExclusive null
        val chatId = create(context) ?: return@withExclusive null
        chatId.takeIf { open(context, it) }
    }
