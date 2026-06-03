package org.openwebdav.messenger.protocol

/**
 * Message envelope framing per `docs/protocol/webdav-layout.md` §5.
 *
 * ```
 * offset size field            value
 * 0      4    magic            ASCII "OWDM"
 * 4      1    envelope-version 0x01
 * 5      1    codec-id         0x00 = none (this feature writes only none)
 * 6      1    flags            MUST be 0x00 in protocol v1
 * 7      1    reserved         MUST be 0x00
 * 8      N    ciphertext-blob  opaque bytes (transport assigns no meaning)
 * ```
 *
 * For the transport feature the post-header blob is opaque: [write] wraps an opaque
 * `ByteArray`, [read] returns it unchanged. The crypto/compression features fill the
 * codec-id and blob slots later without changing this framing.
 */
internal object Envelope {
    /** §5: ASCII "OWDM" magic. */
    val MAGIC = byteArrayOf(0x4F, 0x57, 0x44, 0x4D)

    /** §5 byte 4: this framing version. */
    const val ENVELOPE_VERSION: Byte = 0x01

    /** §5 byte 5: codec-id `none` — the only value this feature writes. */
    const val CODEC_NONE: Byte = 0x00

    /** §5 byte 5: codec-id `deflate` — defined by the spec, wired by the compression feature. */
    const val CODEC_DEFLATE: Byte = 0x01

    /** §5/§7: the closed set of defined codec-ids. Any other value is the §7 reject path. */
    private val DEFINED_CODECS = byteArrayOf(CODEC_NONE, CODEC_DEFLATE)

    /** §5: fixed header size in bytes. */
    const val HEADER_SIZE = 8

    /**
     * §5: wrap an opaque [blob] in the 8-byte header with `codec-id = none`,
     * `flags = 0x00`, `reserved = 0x00`. The returned bytes are exactly what is `PUT`.
     */
    fun write(blob: ByteArray): ByteArray {
        val out = ByteArray(HEADER_SIZE + blob.size)
        MAGIC.copyInto(out, 0)
        out[4] = ENVELOPE_VERSION
        out[5] = CODEC_NONE
        out[6] = 0x00
        out[7] = 0x00
        blob.copyInto(out, HEADER_SIZE)
        return out
    }

    /**
     * §5/§7: parse [fileBytes]. Returns the opaque blob on success, or `null` when the
     * frame is not understood (bad magic, unknown envelope-version, truncated) — the
     * "reject, don't guess" rule (§7). The caller treats `null` as not-a-message / not-ready.
     */
    fun read(fileBytes: ByteArray): ByteArray? {
        if (fileBytes.size < HEADER_SIZE) return null
        for (i in MAGIC.indices) {
            if (fileBytes[i] != MAGIC[i]) return null
        }
        if (fileBytes[4] != ENVELOPE_VERSION) return null
        // §7 "reject, don't guess": codec-id (byte 5) must be one of the defined values
        // {0x00 none, 0x01 deflate}; an unknown codec-id is the same not-understood reject path
        // as bad magic / unknown version. The blob stays opaque — only this byte is validated.
        if (fileBytes[5] !in DEFINED_CODECS) return null
        // flags/reserved (bytes 6-7) are not asserted here so a future minor revision can set
        // reserved bits without breaking this reader of the blob slot.
        return fileBytes.copyOfRange(HEADER_SIZE, fileBytes.size)
    }
}
