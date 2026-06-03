package org.openwebdav.messenger.message

import java.io.ByteArrayOutputStream

/**
 * Big-endian (network byte order) integer codecs shared by the §8 writer ([MessageSerializer]) and
 * reader ([MessageParser] / [ByteCursor]) so an encode and its matching decode live in ONE place and
 * cannot drift (`docs/protocol/webdav-layout.md` §8.1 / §0: every multi-byte integer is big-endian).
 *
 * The uint16 width is the TLV length prefix and `field-count`; the uint64 width is `send-timestamp`
 * (§8.4 tag 0x04). [ByteCursor.u16] is the streaming-read counterpart of [writeUint16Be] for the
 * cursor's incremental parse path; the fixed-width [readUint64Be] here is the counterpart of
 * [writeUint64Be] for a TLV value already split out as its own byte slice.
 */
internal object BigEndian {
    /** Append [value] (0..65535) as a 2-byte big-endian uint16 to [out]. */
    fun writeUint16Be(
        out: ByteArrayOutputStream,
        value: Int,
    ) {
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    /** Append [value] as an 8-byte big-endian uint64 to [out]. */
    fun writeUint64Be(
        out: ByteArrayOutputStream,
        value: Long,
    ) {
        for (i in 7 downTo 0) {
            out.write(((value ushr (8 * i)) and 0xFF).toInt())
        }
    }

    /** Decode an exactly-8-byte big-endian uint64, or `null` if [value] is not exactly 8 bytes wide. */
    fun readUint64Be(value: ByteArray?): Long? {
        if (value == null || value.size != UINT64_BYTES) return null
        var result = 0L
        for (b in value) result = (result shl 8) or (b.toLong() and 0xFF)
        return result
    }

    /** Byte width of a big-endian uint64 — definitional (8 bytes); the §8.4 `send-timestamp` width. */
    const val UINT64_BYTES = 8
}
