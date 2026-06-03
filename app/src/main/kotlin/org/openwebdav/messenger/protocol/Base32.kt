package org.openwebdav.messenger.protocol

/**
 * RFC 4648 Base32 encoders used by the on-disk protocol layout
 * (`docs/protocol/webdav-layout.md` §1.2, §2, §4).
 *
 * Two lowercase, no-padding alphabets are needed:
 *  - [encodeBase32Lower] — standard Base32, alphabet `abcdefghijklmnopqrstuvwxyz234567`,
 *    used for content-hash, inbox-id and sender-tag (§1.2, §2, §4).
 *  - [encodeBase32HexFixed] — Base32hex, alphabet `0123456789abcdefghijklmnopqrstuv`,
 *    big-endian, left-zero-padded to a fixed width, used for the order-token's numeric
 *    fields so lexicographic string order equals numeric order (§4).
 */
internal object Base32 {
    private const val LOWER_ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"
    private const val HEX_ALPHABET = "0123456789abcdefghijklmnopqrstuv"

    /**
     * RFC 4648 Base32 (lowercase, no padding) of [bytes].
     */
    fun encodeBase32Lower(bytes: ByteArray): String = encode(bytes, LOWER_ALPHABET)

    /**
     * RFC 4648 Base32hex (lowercase) of the non-negative [value], big-endian,
     * left-zero-padded (or truncated from the left) to exactly [width] characters.
     *
     * 5 bits per character → [width] characters hold `width * 5` bits.
     */
    fun encodeBase32HexFixed(
        value: Long,
        width: Int,
    ): String {
        require(value >= 0) { "order-token fields are non-negative; got $value" }
        require(width in 1..12) { "width out of supported range: $width" }
        val out = CharArray(width)
        var remaining = value
        for (i in width - 1 downTo 0) {
            val index = (remaining and 0x1F).toInt()
            out[i] = HEX_ALPHABET[index]
            remaining = remaining ushr 5
        }
        return String(out)
    }

    private fun encode(
        bytes: ByteArray,
        alphabet: String,
    ): String {
        if (bytes.isEmpty()) return ""
        val sb = StringBuilder((bytes.size * 8 + 4) / 5)
        var buffer = 0
        var bitsLeft = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                val index = (buffer shr (bitsLeft - 5)) and 0x1F
                sb.append(alphabet[index])
                bitsLeft -= 5
            }
        }
        if (bitsLeft > 0) {
            val index = (buffer shl (5 - bitsLeft)) and 0x1F
            sb.append(alphabet[index])
        }
        return sb.toString()
    }
}
