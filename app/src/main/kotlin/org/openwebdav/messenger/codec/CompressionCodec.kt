package org.openwebdav.messenger.codec

/**
 * Compress/inflate a message plaintext (`docs/architecture.md` decision #4, `docs/protocol/webdav-layout.md` §5
 * byte 5 `codec-id`). Compress-then-encrypt: the plaintext is compressed BEFORE AEAD seal on write, and
 * decompressed AFTER AEAD open on read. Both methods return typed results — never raw throws (SC7).
 *
 * Each call is independent: a per-message new instance so no compressed-byte state leaks across messages
 * (SC6 — CRIME/BREACH guard).
 */
internal interface CompressionCodec {
    /**
     * Compress [plaintext] into a (typically smaller) byte array, or return the original bytes unchanged
     * when compression does not reduce size. Never throws — a runtime compression failure is a caller error
     * that should surface as a typed rejection on the write path.
     */
    fun compress(plaintext: ByteArray): ByteArray

    /**
     * Inflate [compressed] back to the original plaintext. The compressed input MUST be exactly the bytes
     * produced by [compress] for the same codec. A corrupted/oversize/malformed input results in a typed
     * [DecompressResult.Rejected] — never a crash (SC7 bounded decompression).
     */
    fun decompress(compressed: ByteArray): DecompressResult
}
