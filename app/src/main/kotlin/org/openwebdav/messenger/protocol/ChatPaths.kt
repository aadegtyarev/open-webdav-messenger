package org.openwebdav.messenger.protocol

/**
 * Chat-root-relative paths for the folder layout in `docs/protocol/webdav-layout.md` §1.
 * All paths are relative to the chat-root (which lives in connection config, never on disk, §1.1).
 */
internal object ChatPaths {
    /** §1: `meta/` collection. */
    const val META = "meta"

    /** §1: `inbox/` parent collection. */
    const val INBOX = "inbox"

    /** §1.2: `inbox/<recipient-inbox-id>/` for a recipient in a chat. */
    fun inbox(
        recipientIdentifier: String,
        chatId: String,
    ): String = "$INBOX/${MessageId.inboxId(recipientIdentifier, chatId)}"

    /**
     * §2/§3: the content-addressed message path inside a recipient inbox.
     * The file name **is** the message-id = `order-token "~" content-hash(fileBytes)`.
     *
     * @param fileBytes the exact framed envelope bytes that will be PUT (the hash is over these).
     */
    fun message(
        inboxPath: String,
        orderToken: String,
        fileBytes: ByteArray,
    ): String = "$inboxPath/${MessageId.messageId(orderToken, fileBytes)}"
}
