package org.openwebdav.messenger.protocol

import java.security.MessageDigest

/**
 * Content-addressed naming and inbox-id derivation per `docs/protocol/webdav-layout.md`.
 *
 *  - [contentHash] — §2/§3: `b32lower(SHA-256(file-bytes))[0:32]`.
 *  - [messageId] — §2: `order-token "~" content-hash`.
 *  - [splitMessageId] — inverse of [messageId] (split on the single `~`).
 *  - [inboxId] — §1.2: `b32lower(SHA-256(recipient ‖ 0x1F ‖ chat-id))[0:26]`.
 */
internal object MessageId {
    /** §2 separator between order-token and content-hash. */
    const val SEPARATOR = '~'

    /** §2: content-hash length in Base32 characters. */
    private const val CONTENT_HASH_LEN = 32

    /** §1.2: recipient-inbox-id length in Base32 characters. */
    private const val INBOX_ID_LEN = 26

    /** §1.2 domain separator (ASCII Unit Separator). */
    private const val UNIT_SEPARATOR: Byte = 0x1F

    /**
     * §2/§3: `b32lower(SHA-256(file-bytes))[0:32]`.
     * Computed over the exact bytes written to / read from disk (the full envelope, §5).
     */
    fun contentHash(fileBytes: ByteArray): String = Base32.encodeBase32Lower(sha256(fileBytes)).substring(0, CONTENT_HASH_LEN)

    /**
     * §2: the content-addressed file name. The [orderToken] is supplied by [OrderToken];
     * the content-hash is derived from [fileBytes].
     */
    fun messageId(
        orderToken: String,
        fileBytes: ByteArray,
    ): String = orderToken + SEPARATOR + contentHash(fileBytes)

    /**
     * Split a message-id into (order-token, content-hash) on the single `~` separator (§2).
     * Returns `null` for a name that is not a well-formed message-id.
     */
    fun splitMessageId(name: String): Pair<String, String>? {
        val sep = name.indexOf(SEPARATOR)
        if (sep <= 0 || sep == name.length - 1) return null
        if (name.indexOf(SEPARATOR, sep + 1) != -1) return null
        val hash = name.substring(sep + 1)
        if (hash.length != CONTENT_HASH_LEN) return null
        return name.substring(0, sep) to hash
    }

    /**
     * §1.2: `b32lower(SHA-256(utf8(recipient) ‖ 0x1F ‖ utf8(chatId)))[0:26]`.
     * Deterministic and sender-computable: any sender derives the same inbox path
     * for a recipient in a given chat.
     */
    fun inboxId(
        recipientIdentifier: String,
        chatId: String,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(recipientIdentifier.toByteArray(Charsets.UTF_8))
        digest.update(UNIT_SEPARATOR)
        digest.update(chatId.toByteArray(Charsets.UTF_8))
        return Base32.encodeBase32Lower(digest.digest()).substring(0, INBOX_ID_LEN)
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}
