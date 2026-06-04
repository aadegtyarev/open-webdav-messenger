package org.openwebdav.messenger.protocol

import java.io.ByteArrayOutputStream

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

    /** §4: order-token length in characters (the §2 prefix before the `~`). */
    private const val ORDER_TOKEN_LEN = 29

    /** §2: content-hash alphabet (RFC 4648 Base32 lowercase, no padding). */
    private val CONTENT_HASH_CHARS = ('a'..'z').toSet() + ('2'..'7').toSet()

    /** §1.2: recipient-inbox-id length in Base32 characters. */
    private const val INBOX_ID_LEN = 26

    /** §1.2 domain separator (ASCII Unit Separator). */
    private const val UNIT_SEPARATOR: Byte = 0x1F

    /**
     * §2/§3: `b32lower(SHA-256(file-bytes))[0:32]`.
     * Computed over the exact bytes written to / read from disk (the full envelope, §5).
     */
    fun contentHash(fileBytes: ByteArray): String = HashTag.tag(fileBytes, CONTENT_HASH_LEN)

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
     * §2 well-formedness check for a CONTENT-ADDRESSED REFERENCE (a `reply-to` / `target-id` value):
     * `true` iff [name] is a syntactically valid §2 file name — the `~` split is unambiguous, the
     * order-token is exactly 29 chars over `[0-9a-z-]`, and the content-hash is exactly 32 chars over
     * `[a-z2-7]`. This validates well-formedness ONLY (alphabet + the two component lengths/charsets):
     * a well-formed reference to a not-yet-received message is valid (resolution is the reader's concern,
     * §4 causality / §8.6) — only a malformed string is rejected.
     */
    fun isWellFormedMessageId(name: String): Boolean {
        val (orderToken, contentHash) = splitMessageId(name) ?: return false
        if (orderToken.length != ORDER_TOKEN_LEN) return false
        if (orderToken.any { it !in HashTag.ORDER_TOKEN_CHARS }) return false
        // splitMessageId already pins CONTENT_HASH_LEN; re-check the alphabet here.
        return contentHash.all { it in CONTENT_HASH_CHARS }
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
        // §1.2 domain-separated input: utf8(recipient) ‖ 0x1F ‖ utf8(chatId), hashed as one stream.
        val composed =
            ByteArrayOutputStream().apply {
                write(recipientIdentifier.toByteArray(Charsets.UTF_8))
                write(UNIT_SEPARATOR.toInt())
                write(chatId.toByteArray(Charsets.UTF_8))
            }.toByteArray()
        return HashTag.tag(composed, INBOX_ID_LEN)
    }
}
