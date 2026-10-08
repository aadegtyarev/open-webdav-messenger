package org.openwebdav.messenger.app

import org.openwebdav.messenger.account.AccountMutationBarrier
import org.openwebdav.messenger.chatdirectory.ChatAccess
import org.openwebdav.messenger.keystore.ChatRegistry

/** Commits a descriptor result only against a fresh registry and current account/runtime context. */
internal class ChatAccessRegistryCommit(
    private val barrier: AccountMutationBarrier,
    private val loadRows: (String) -> List<ChatRegistry.Entry>?,
    private val replaceRows: (String, List<ChatRegistry.Entry>) -> Boolean,
) {
    suspend fun commit(
        communityId: String,
        chatId: String,
        access: ChatAccess,
        expectedGeneration: Long,
        contextCurrent: () -> Boolean,
    ): ChatAccess? =
        barrier.withStableAccount {
            if (!isCurrent(expectedGeneration, contextCurrent)) return@withStableAccount null
            val rows = runCatching { loadRows(communityId) }.getOrNull() ?: return@withStableAccount null
            val match = rows.singleOrNull { it.id == chatId } ?: return@withStableAccount null
            if (match.kind != "group") return@withStableAccount null
            val current =
                when (match.access) {
                    "public" -> ChatAccess.PUBLIC
                    "private" -> ChatAccess.PRIVATE
                    "unknown" -> null
                    else -> return@withStableAccount null
                }
            if (current != null) return@withStableAccount current
            if (!isCurrent(expectedGeneration, contextCurrent)) return@withStableAccount null
            val merged = rows.map { if (it.id == chatId) it.copy(access = access.name.lowercase()) else it }
            if (runCatching { replaceRows(communityId, merged) }.getOrDefault(false)) access else null
        }

    private fun isCurrent(
        generation: Long,
        contextCurrent: () -> Boolean,
    ): Boolean = barrier.replacementGeneration() == generation && runCatching(contextCurrent).getOrDefault(false)
}
