package org.openwebdav.messenger.codec

import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Raw DEFLATE (RFC 1951, nowrap) compress/inflate per `docs/architecture.md` decision #4:
 *
 *  - **`Deflater.BEST_COMPRESSION`** — we trade CPU for bytes (messages are small, WebDAV is the bottleneck).
 *  - **`nowrap` mode** — raw DEFLATE, no zlib/gzip header. Saves 2+ bytes per message.
 *  - **Decompress bound: 1 MiB** — zip-bomb guard (SC7). An inflated output exceeding this bound is
 *    [DecompressResult.Rejected], never an OOM.
 *  - **Per-message independent** — a new [Deflater]/[Inflater] per call, never reused across messages.
 *    This prevents an attacker from co-compressing their data with a secret (CRIME/BREACH guard, SC6).
 *  - **Decompress rounding to original** — after `deflate.finished()` the compressor reads remaining buffer
 *    bytes; the inflater's `needsInput()` / `finished()` sequence mirrors standard DEFLATE round-trip
 *    patterns with `nowrap`.
 */
internal class DeflateCodec : CompressionCodec {
    /**
     * 1 MiB — generous headroom for the decompressed plaintext of a text-only message while bounding the
     * zip-bomb OOM-DoS surface (SC7). Same value as the transport's [MAX_MESSAGE_FILE_BYTES].
     */
    private val maxDecompressBytes = 1L * 1024 * 1024

    override fun compress(plaintext: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true) // nowrap = true
        try {
            deflater.setInput(plaintext)
            deflater.finish()
            val out = mutableListOf<ByteArray>()
            var total = 0
            val buf = ByteArray(4096)
            while (!deflater.finished()) {
                val n = deflater.deflate(buf)
                if (n > 0) {
                    out.add(buf.copyOf(n))
                    total += n
                }
            }
            val result = ByteArray(total)
            var pos = 0
            for (chunk in out) {
                chunk.copyInto(result, pos)
                pos += chunk.size
            }
            return result
        } finally {
            deflater.end()
        }
    }

    override fun decompress(compressed: ByteArray): DecompressResult {
        val inflater = Inflater(true) // nowrap = true
        try {
            inflater.setInput(compressed)
            val out = mutableListOf<ByteArray>()
            var total = 0
            val buf = ByteArray(4096)
            while (!inflater.finished()) {
                val n =
                    try {
                        inflater.inflate(buf)
                    } catch (e: DataFormatException) {
                        return DecompressResult.Rejected
                    }
                if (n == 0) {
                    // No bytes produced this round. If the inflater is finished (the final block
                    // marker was consumed), the stream is complete — break cleanly. If it needs
                    // more input but none is available, the stream is truncated/corrupt.
                    if (inflater.finished()) break
                    if (inflater.needsInput()) return DecompressResult.Rejected
                    // Neither finished nor needsInput → internal buffer full, retry inflate.
                    continue
                }
                total += n
                if (total > maxDecompressBytes) return DecompressResult.Rejected // zip-bomb guard
                out.add(buf.copyOf(n))
            }
            val result = ByteArray(total)
            var pos = 0
            for (chunk in out) {
                chunk.copyInto(result, pos)
                pos += chunk.size
            }
            return DecompressResult.Ok(result)
        } finally {
            inflater.end()
        }
    }
}
