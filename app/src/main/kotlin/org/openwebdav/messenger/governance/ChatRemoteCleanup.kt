package org.openwebdav.messenger.governance

import org.openwebdav.messenger.chatdirectory.ChatDirectoryCrypto
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.protocol.ChatPaths
import org.openwebdav.messenger.transport.WebDavResult
import org.openwebdav.messenger.transport.WebDavTransport

/** Selectively removes verified chat-scoped mutable WebDAV content; shared roots and governance stay intact. */
internal class ChatRemoteCleanup(
    private val transport: WebDavTransport,
    private val descriptorCrypto: ChatDirectoryCrypto,
) {
    suspend fun clean(
        chatId: String,
        communityKey: ChatKey,
    ): ChatRemoteCleanupResult {
        var deleted = 0
        var pending = mutableListOf<String>()
        for (path in listOf("${ChatPaths.LOG}/$chatId", "private-membership/$chatId")) {
            val names = names(path)
            if (names == null) {
                pending += "Could not list $path"
                continue
            }
            var complete = true
            for (name in names) if (delete("$path/$name")) deleted++ else complete = false
            if (complete && delete(path)) {
                deleted++
            } else if (complete) {
                pending += "Could not remove $path"
            } else {
                pending += "Some files remain in $path"
            }
        }
        ChatDirectoryCleanup(transport, descriptorCrypto).clean(chatId, communityKey).also {
            deleted += it.deletedObjects
            pending += it.pendingReasons
        }
        ChatChangeCleanup(transport).clean(chatId).also {
            deleted += it.deletedObjects
            pending += it.pendingReasons
        }
        pending += "Read receipts cannot be matched to a chat in the current path format"
        return ChatRemoteCleanupResult(deleted, pending.distinct())
    }

    private suspend fun names(path: String): List<String>? =
        when (val result = transport.listBounded(path, MAX_LIST_ENTRIES, MAX_LIST_BYTES)) {
            is WebDavResult.Success -> result.value.map { it.name }
            is WebDavResult.TransportError -> if (result.code == 404) emptyList() else null
            else -> null
        }

    private suspend fun delete(path: String): Boolean = transport.delete(path) is WebDavResult.Success

    private companion object {
        const val MAX_LIST_ENTRIES = 256
        const val MAX_LIST_BYTES = 512 * 1024
    }
}
