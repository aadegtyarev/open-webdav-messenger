package org.openwebdav.messenger.codec

import org.openwebdav.messenger.protocol.Envelope

/**
 * The closed set of compression codecs the envelope header's `codec-id` byte (§5) can name.
 * `reject-don't-guess`: an unknown id is a typed rejection — never a best-effort fallback (§7).
 */
internal enum class Codec(val id: Byte) {
    /** No compression — plaintext stored verbatim. */
    NONE(Envelope.CODEC_NONE),

    /** Raw DEFLATE (RFC 1951, nowrap — no zlib/gzip header). Implemented by [DeflateCodec]. */
    DEFLATE(Envelope.CODEC_DEFLATE),
    ;

    companion object {
        /**
         * §7 explicit codec dispatch: returns the [Codec] for [id], or throws [IllegalArgumentException]
         * for an unknown value. The caller must map the exception to a typed rejection — never guess.
         */
        fun fromId(id: Byte): Codec =
            when (id) {
                Envelope.CODEC_NONE -> NONE
                Envelope.CODEC_DEFLATE -> DEFLATE
                else -> throw IllegalArgumentException("unknown codec-id: 0x${id.toUByte().toString(16)}")
            }
    }
}
