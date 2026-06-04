package org.openwebdav.messenger.protocol

/**
 * Lowercase hex encoding for byte arrays. Extracted (review finding 7) so the next layer needing hex
 * reuses it instead of re-rolling a nibble loop. Today used by `data/MessageStore` to key a row by the
 * sender's Ed25519 public key. Behaviour identical to the prior hand-rolled `MessageStore.toHex`.
 */
internal object Hex {
    private const val HEX = "0123456789abcdef"

    /** Lowercase hex of [bytes] (two chars per byte, no separator). */
    fun encode(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }
}
