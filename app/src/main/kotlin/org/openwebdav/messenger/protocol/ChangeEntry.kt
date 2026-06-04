package org.openwebdav.messenger.protocol

/**
 * The §9.2 change-entry name format (`docs/protocol/webdav-layout.md`, generation 2):
 *
 * ```
 * change-entry-name = chat-tag "~" order-token
 * chat-tag          = b32lower(SHA-256(utf8(chat-id)))[0:16]   ; 16 chars [a-z2-7] — which chat changed
 * order-token       = the §4 order-token of the just-written log/ message (29 chars) — the new cursor coord
 * ```
 *
 * A change entry is a tiny, cursor-addressed file: its name *is* its meaning (no body). Because the
 * name encodes the coordinate, two senders notifying the same member about the same chat produce two
 * distinct entries — never a competing rewrite (so the write is unconditional, §6/§9.2). A reader
 * takes the **maximum** order-token per chat-tag as the high-water mark — NOT last-write-wins
 * (arch note §35: entries are not ordered by arrival on disk).
 *
 * Pure/stateless string logic, consistent with the rest of `protocol/`.
 */
internal object ChangeEntry {
    /** §9.2 separator between chat-tag and order-token. Reuses the §2 `~` (unambiguous: both alphabets exclude it). */
    const val SEPARATOR = '~'

    /** §9.2: chat-tag length in Base32 characters. */
    private const val CHAT_TAG_LEN = 16

    /** §9.2 chat-tag alphabet (RFC 4648 Base32 lowercase, no padding). */
    private val CHAT_TAG_CHARS = ('a'..'z').toSet() + ('2'..'7').toSet()

    /** §9.2: `b32lower(SHA-256(utf8(chat-id)))[0:16]` — which chat changed (filename-safe, fixed-length). */
    fun chatTag(chatId: String): String = HashTag.tag(chatId, CHAT_TAG_LEN)

    /** §9.2: assemble the change-entry name `chat-tag "~" order-token`. */
    fun name(
        chatId: String,
        orderToken: String,
    ): String = chatTag(chatId) + SEPARATOR + orderToken

    /**
     * §9.2: parse a change-entry file name into (chat-tag, order-token), or `null` when it is not a
     * well-formed change entry — chat-tag exactly 16 chars over `[a-z2-7]`, a single `~`, and an
     * order-token exactly [OrderToken.LENGTH] chars over `[0-9a-z-]`. A malformed entry is ignored,
     * not surfaced (a tampered/foreign file in the shared `changes/` folder must not crash a poll).
     */
    fun parse(name: String): Parsed? {
        val sep = name.indexOf(SEPARATOR)
        if (sep != CHAT_TAG_LEN) return null
        if (name.indexOf(SEPARATOR, sep + 1) != -1) return null
        val chatTag = name.substring(0, sep)
        val orderToken = name.substring(sep + 1)
        if (orderToken.length != OrderToken.LENGTH) return null
        if (chatTag.any { it !in CHAT_TAG_CHARS }) return null
        if (orderToken.any { it !in HashTag.ORDER_TOKEN_CHARS }) return null
        return Parsed(chatTag, orderToken)
    }

    /** A well-formed change entry: which chat changed ([chatTag]) and to which cursor coordinate ([orderToken]). */
    data class Parsed(val chatTag: String, val orderToken: String)
}
