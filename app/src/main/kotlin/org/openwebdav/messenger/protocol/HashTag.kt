package org.openwebdav.messenger.protocol

import java.security.MessageDigest

/**
 * The shared `b32lower(SHA-256(x))[0:N]` tag primitive used across the on-disk protocol layout
 * (`docs/protocol/webdav-layout.md`): the §1.2 `member-index-id` (N=26), the §2 content-hash (N=32),
 * the §4 `sender-tag` (N=8), and the §9.2 `chat-tag` (N=16) are all this one function with different
 * truncation lengths. Extracted so the digest + Base32 + truncate pattern lives once, not re-minted in
 * [MessageId], [OrderToken], and [ChangeEntry] (review finding 6 — behaviour byte-identical).
 *
 * Also the single home for the §4 order-token alphabet, referenced by both [MessageId] and
 * [ChangeEntry] for their well-formedness checks (one definition, not three).
 *
 * Pure/stateless, consistent with the rest of `protocol/`.
 */
internal object HashTag {
    /** §4 order-token alphabet (`ts-millis` "-" `sender-tag` "-" `seq`): `[0-9a-z-]`. */
    val ORDER_TOKEN_CHARS: Set<Char> = ('0'..'9').toSet() + ('a'..'z').toSet() + '-'

    /** `b32lower(SHA-256([bytes]))[0:length]` — the shared tag function (hash over raw bytes). */
    fun tag(
        bytes: ByteArray,
        length: Int,
    ): String = Base32.encodeBase32Lower(sha256(bytes)).substring(0, length)

    /** `b32lower(SHA-256(utf8([value])))[0:length]` — the common UTF-8 string form (chat-tag, sender-tag). */
    fun tag(
        value: String,
        length: Int,
    ): String = tag(value.toByteArray(Charsets.UTF_8), length)

    /** SHA-256 of [bytes] — exposed for callers that hash a composed byte stream (e.g. §1.2 domain-separated). */
    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}
