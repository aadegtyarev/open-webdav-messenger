package org.openwebdav.messenger.app

import org.openwebdav.messenger.account.AccountMutationBarrier
import org.openwebdav.messenger.chatdirectory.ChatAccess
import org.openwebdav.messenger.keystore.ChatRegistry

/** Commits a descriptor result only against a fresh registry and current account/runtime context. */
internal class ChatAccessRegistryCommit(
    private val barrier: AccountMutationBarrier,
    private val updateRows: (String, (List<ChatRegistry.Entry>) -> List<ChatRegistry.Entry>?) -> Boolean,
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
            var committedAccess: ChatAccess? = null
            val updated =
                runCatching {
                    updateRows(communityId) { rows ->
                        val match = rows.singleOrNull { it.id == chatId } ?: return@updateRows null
                        if (match.kind != "group") return@updateRows null
                        val current =
                            when (match.access) {
                                "public" -> ChatAccess.PUBLIC
                                "private" -> ChatAccess.PRIVATE
                                "unknown" -> null
                                else -> return@updateRows null
                            }
                        if (current != null) {
                            committedAccess = current
                            rows
                        } else if (!isCurrent(expectedGeneration, contextCurrent)) {
                            null
                        } else {
                            committedAccess = access
                            rows.map { if (it.id == chatId) it.copy(access = access.name.lowercase()) else it }
                        }
                    }
                }.getOrDefault(false)
            committedAccess.takeIf { updated }
        }

    private fun isCurrent(
        generation: Long,
        contextCurrent: () -> Boolean,
    ): Boolean = barrier.replacementGeneration() == generation && runCatching(contextCurrent).getOrDefault(false)
}
