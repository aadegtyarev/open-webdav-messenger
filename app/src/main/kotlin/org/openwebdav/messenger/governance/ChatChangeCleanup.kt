package org.openwebdav.messenger.governance

import org.openwebdav.messenger.protocol.ChangeEntry
import org.openwebdav.messenger.protocol.ChatPaths
import org.openwebdav.messenger.transport.WebDavResult
import org.openwebdav.messenger.transport.WebDavTransport

/** Selectively removes change markers whose canonical chat tag matches the deleted chat. */
internal class ChatChangeCleanup(private val transport: WebDavTransport) {
    suspend fun clean(chatId: String): ChatRemoteCleanupResult {
        val members = names(ChatPaths.CHANGES) ?: return pending("Could not list changes")
        if (members.size > MAX_MEMBER_INDEXES) return pending("Too many change indexes to scan safely")
        val targetTag = ChangeEntry.chatTag(chatId)
        var deleted = 0
        val pending = mutableListOf<String>()
        for (member in members) {
            if (!safeName(member)) continue
            val path = "${ChatPaths.CHANGES}/$member"
            val entries = names(path)
            if (entries == null) {
                pending += "Could not list $path"
                continue
            }
            for (entry in entries) {
                if (ChangeEntry.parse(entry)?.chatTag == targetTag) {
                    when (transport.delete("$path/$entry")) {
                        is WebDavResult.Success -> deleted++
                        else -> pending += "Could not delete $path/$entry"
                    }
                }
            }
        }
        return ChatRemoteCleanupResult(deleted, pending)
    }

    private suspend fun names(path: String): List<String>? =
        when (val result = transport.listBounded(path, MAX_ENTRIES, MAX_LIST_BYTES)) {
            is WebDavResult.Success -> result.value.map { it.name }
            is WebDavResult.TransportError -> if (result.code == 404) emptyList() else null
            else -> null
        }

    private fun safeName(name: String): Boolean =
        name.isNotEmpty() && name.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in "._~-" }

    private fun pending(reason: String) = ChatRemoteCleanupResult(0, listOf(reason))

    private companion object {
        const val MAX_ENTRIES = 256
        const val MAX_LIST_BYTES = 512 * 1024
        const val MAX_MEMBER_INDEXES = 64
    }
}
