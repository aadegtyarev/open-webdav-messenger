package org.openwebdav.messenger.governance

import org.openwebdav.messenger.chatdirectory.ChatDirectoryCrypto
import org.openwebdav.messenger.chatdirectory.ChatDirectoryPaths
import org.openwebdav.messenger.chatdirectory.ChatParseResult
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.protocol.Envelope
import org.openwebdav.messenger.protocol.Hex
import org.openwebdav.messenger.transport.ReadResult
import org.openwebdav.messenger.transport.WebDavResult
import org.openwebdav.messenger.transport.WebDavTransport

/** Deletes only chat-directory entries whose signed, decrypted descriptor names the target chat. */
internal class ChatDirectoryCleanup(
    private val transport: WebDavTransport,
    private val crypto: ChatDirectoryCrypto,
) {
    suspend fun clean(
        chatId: String,
        key: ChatKey,
    ): ChatRemoteCleanupResult {
        val names =
            when (val result = transport.listBounded(ChatDirectoryPaths.CHAT_DIRECTORY, MAX_ENTRIES, MAX_LIST_BYTES)) {
                is WebDavResult.Success -> result.value.map { it.name }
                is WebDavResult.TransportError -> if (result.code == 404) emptyList() else return pending("Could not list chat-directory")
                else -> return pending("Could not list chat-directory")
            }
        var deleted = 0
        val pending = mutableListOf<String>()
        for (name in names) {
            if (!ChatDirectoryPaths.isWellFormedEntryName(name)) {
                pending += "Unverifiable chat-directory entry retained"
                continue
            }
            val path = "${ChatDirectoryPaths.CHAT_DIRECTORY}/$name"
            val ready =
                (transport.readContentAddressedBounded(path, name, MAX_DESCRIPTOR_BYTES) as? WebDavResult.Success)
                    ?.value as? ReadResult.Ready
            if (ready == null) {
                pending += "Could not verify chat-directory entry $name"
                continue
            }
            val envelope = Envelope.frame(ready.codecId, ready.blob)
            val parsed = crypto.openDescriptor(envelope, key) as? ChatParseResult.Parsed
            if (parsed?.descriptor?.chatId?.let { Hex.encode(it) } == chatId) {
                when (transport.delete(path)) {
                    is WebDavResult.Success -> deleted++
                    else -> pending += "Could not delete chat-directory entry $name"
                }
            }
        }
        return ChatRemoteCleanupResult(deleted, pending)
    }

    private fun pending(reason: String) = ChatRemoteCleanupResult(0, listOf(reason))

    private companion object {
        const val MAX_ENTRIES = 256
        const val MAX_LIST_BYTES = 512 * 1024
        const val MAX_DESCRIPTOR_BYTES = 4096
    }
}
