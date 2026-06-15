package org.openwebdav.messenger.app

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.openwebdav.messenger.protocol.ChatPaths
import org.openwebdav.messenger.sync.SyncEngine
import org.openwebdav.messenger.transport.WebDavResult
import org.openwebdav.messenger.transport.WebDavTransport

/**
 * Writes and reads read receipts on the WebDAV disk.
 * A receipt is a tiny file: `read/<member-id>/<chat-id>/<order-token>`.
 */
internal class ReadReceiptService(
    private val transport: WebDavTransport,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Write a read receipt: "I read up to this order-token in this chat." */
    suspend fun writeReceipt(
        memberId: String,
        chatId: String,
        orderToken: String,
    ) = withContext(ioDispatcher) {
        val dir = ChatPaths.readReceiptDir(memberId, chatId)
        transport.ensureCollection(dir)
        transport.write(
            ChatPaths.readReceiptFile(memberId, chatId, orderToken),
            byteArrayOf(0x00),
        )
    }

    /** Get the latest read cursor for a member in a chat, or null. */
    suspend fun latestReceipt(
        memberId: String,
        chatId: String,
    ): String? = withContext(ioDispatcher) {
        val dir = ChatPaths.readReceiptDir(memberId, chatId)
        when (val result = transport.list(dir)) {
            is WebDavResult.Success ->
                result.value
                    .map { it.name }
                    .maxOrNull() // lexicographic max = latest order-token
            else -> null
        }
    }
}
