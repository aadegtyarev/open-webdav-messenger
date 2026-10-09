package org.openwebdav.messenger.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

internal object ChatDeletionStateStore {
    fun observeDeletedChatKeys(context: Context?): Flow<Set<Pair<String, String>>> {
        val appContext = context?.applicationContext ?: return flowOf(emptySet())
        if (!appContext.getDatabasePath(MessengerDatabase.DB_NAME).exists()) return flowOf(emptySet())
        val dao = MessengerDatabase.get(appContext).messageDao()
        return combine(dao.observeHiddenDms(), dao.observeChatDeletions()) { hidden, deletions ->
            (hidden.map { it.communityId to it.chatId } + deletions.map { it.communityId to it.chatId }).toSet()
        }
    }

    suspend fun isChatDeleted(
        context: Context?,
        communityId: String,
        chatId: String,
    ): Boolean {
        val appContext = context?.applicationContext ?: return false
        if (!appContext.getDatabasePath(MessengerDatabase.DB_NAME).exists()) return false
        return runCatching {
            MessengerDatabase.get(appContext).messageDao().isChatDeleted(communityId, chatId)
        }.getOrDefault(true)
    }
}
