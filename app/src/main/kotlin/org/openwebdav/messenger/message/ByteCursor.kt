package org.openwebdav.messenger.message

/**
 * A bounds-checked forward reader over a `ByteArray`, shared by every untrusted-byte parse path:
 * the §8 message reader ([MessageParser] / [TlvFields]) and the §10.3 / §11.3 community-directory inner
 * codecs (`DirectoryEntryCodec`, `ChatDescriptorCodec`) — `docs/protocol/webdav-layout.md`.
 *
 * Reads are bounded by [limit] (default: the whole buffer). The bounded forms pass `signatureStart` so a
 * read never crosses into a trailing fixed-width signature; the message reader leaves [limit] at the
 * buffer end and bounds the field region itself in [TlvFields]. In every case each read validates the
 * requested span against the remaining bytes and returns `null` on overrun instead of throwing — so no
 * index/bounds exception ever escapes to the parser's caller (§8.1 / §10.3 / §11.3 reject-don't-guess;
 * stack-notes Kotlin null-safety: no `!!` on parse paths). Big-endian.
 */
internal class ByteCursor(
    private val buf: ByteArray,
    private val limit: Int = buf.size,
) {
    var pos: Int = 0
        private set

    private val remaining: Int get() = limit - pos

    /** Read [n] bytes as a fresh array, or `null` if fewer than [n] remain (or [n] is negative). */
    fun take(n: Int): ByteArray? {
        if (n < 0 || n > remaining) return null
        val out = buf.copyOfRange(pos, pos + n)
        pos += n
        return out
    }

    /** Read one unsigned byte (0..255), or `null` if none remain. */
    fun u8(): Int? {
        if (remaining < 1) return null
        return buf[pos++].toInt() and 0xFF
    }

    /** Read a big-endian uint16 (0..65535), or `null` if fewer than 2 bytes remain. */
    fun u16(): Int? {
        if (remaining < 2) return null
        val hi = buf[pos].toInt() and 0xFF
        val lo = buf[pos + 1].toInt() and 0xFF
        pos += 2
        return (hi shl 8) or lo
    }

    /** Read a big-endian uint64, or `null` if fewer than 8 bytes remain. */
    fun u64(): Long? {
        if (remaining < 8) return null
        var result = 0L
        for (i in 0 until 8) result = (result shl 8) or (buf[pos + i].toLong() and 0xFF)
        pos += 8
        return result
    }
}
