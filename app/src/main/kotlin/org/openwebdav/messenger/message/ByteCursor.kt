package org.openwebdav.messenger.message

/**
 * A bounds-checked forward reader over a `ByteArray` for the §8 parse path
 * (`docs/protocol/webdav-layout.md`). Every read validates the requested span against the remaining
 * buffer and returns `null` on overrun instead of throwing — so no index/bounds exception ever escapes
 * to the parser's caller (§8.1; stack-notes Kotlin null-safety: no `!!` on parse paths). Big-endian.
 */
internal class ByteCursor(private val buf: ByteArray) {
    var pos: Int = 0
        private set

    val remaining: Int get() = buf.size - pos

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
}
