package org.openwebdav.messenger.protocol

import java.security.MessageDigest

/**
 * Ordering token per `docs/protocol/webdav-layout.md` §4 — a monotonic, per-sender,
 * best-effort token used only for display ordering (dedup is by message-id, §2).
 *
 * ```
 * order-token = ts-millis "-" sender-tag "-" seq       ; 29 chars, lexicographically sortable
 * ts-millis   = b32hex-fixed(unix-millis, 11)          ; 11 chars
 * sender-tag  = b32lower(SHA-256(utf8(sender)))[0:8]   ; 8 chars, [a-z2-7]
 * seq         = b32hex-fixed(per-sender-counter, 8)    ; 8 chars
 * ```
 *
 * Total = 11 + 1 + 8 + 1 + 8 = 29 chars; the message-id (order-token "~" content-hash) is
 * 29 + 1 + 32 = 62 chars (§2).
 *
 * Base32hex preserves numeric order under lexicographic string comparison, so a plain
 * string sort of the token equals a numeric sort of `(ts, sender, seq)`.
 */
internal object OrderToken {
    private const val TS_WIDTH = 11
    private const val SENDER_TAG_LEN = 8
    private const val SEQ_WIDTH = 8

    /** §4: total order-token length = 11 + 1 + 8 + 1 + 8. */
    const val LENGTH = TS_WIDTH + 1 + SENDER_TAG_LEN + 1 + SEQ_WIDTH

    /**
     * Build an order-token from the wall-clock [unixMillis], the [senderIdentifier],
     * and a strictly-increasing per-sender [seq] counter.
     */
    fun build(
        unixMillis: Long,
        senderIdentifier: String,
        seq: Long,
    ): String {
        val ts = Base32.encodeBase32HexFixed(unixMillis, TS_WIDTH)
        val tag = senderTag(senderIdentifier)
        val seqField = Base32.encodeBase32HexFixed(seq, SEQ_WIDTH)
        return "$ts-$tag-$seqField"
    }

    /** §4: `b32lower(SHA-256(utf8(sender)))[0:8]` — a stable per-sender namespace tag. */
    fun senderTag(senderIdentifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(senderIdentifier.toByteArray(Charsets.UTF_8))
        return Base32.encodeBase32Lower(digest).substring(0, SENDER_TAG_LEN)
    }
}
