package org.openwebdav.messenger.codec

/**
 * The outcome of [CompressionCodec.decompress]: the recovered plaintext, or a typed rejection. Never a raw
 * exception (SC7 — decompression failure is an error path, not a crash).
 */
internal sealed interface DecompressResult {
    /** Decompression succeeded; [bytes] is the exact original plaintext. */
    data class Ok(val bytes: ByteArray) : DecompressResult {
        override fun equals(other: Any?): Boolean = other is Ok && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    /** Decompression failed: corrupted/malformed compressed data, or the inflated size exceeds the bound. */
    data object Rejected : DecompressResult
}
