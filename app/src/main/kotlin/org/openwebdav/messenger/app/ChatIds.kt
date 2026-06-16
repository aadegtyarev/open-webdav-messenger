package org.openwebdav.messenger.app

import org.openwebdav.messenger.crypto.NativeCrypto
import org.openwebdav.messenger.protocol.Hex

/**
 * Deterministic DM chat-id from two X25519 box public keys. Both sides compute the same chat-id
 * independently — no negotiation, no directory publishing. The chat-id is not secret; it names the
 * chat on the disk. Security comes from the per-pair DH-derived [ChatKey], not from the id.
 *
 * Domain-separated with a versioned context prefix so a future scheme change never collides:
 *   BLAKE2b("owdm/dm-chat/v1" ‖ 0x1F ‖ sort(aliceBoxPub, bobBoxPub)) → hex(32)
 */
internal object ChatIds {
    private val DM_CHAT_CONTEXT: ByteArray = "owdm/dm-chat/v1".toByteArray(Charsets.UTF_8)
    private const val DOMAIN_SEPARATOR: Byte = 0x1F
    private const val HASH_BYTES = 32

    /**
     * Compute the deterministic DM chat-id from my X25519 public key and the peer's X25519 public key.
     * The two keys are sorted lexicographically before hashing so both sides compute the same result.
     */
    fun dmChatId(
        native: NativeCrypto,
        myBoxPub: ByteArray,
        peerBoxPub: ByteArray,
    ): String {
        val sorted =
            if (compareLex(myBoxPub, peerBoxPub) <= 0) {
                myBoxPub + peerBoxPub
            } else {
                peerBoxPub + myBoxPub
            }
        val input = DM_CHAT_CONTEXT + byteArrayOf(DOMAIN_SEPARATOR) + sorted
        val hash = native.genericHash(input, HASH_BYTES)
        return Hex.encode(hash)
    }

    /** Lexicographic unsigned byte comparison — same pattern as [org.openwebdav.messenger.identity.IdentityCrypto.compareLex]. */
    private fun compareLex(
        x: ByteArray,
        y: ByteArray,
    ): Int {
        val n = minOf(x.size, y.size)
        for (i in 0 until n) {
            val c = (x[i].toInt() and 0xFF) - (y[i].toInt() and 0xFF)
            if (c != 0) return c
        }
        return x.size - y.size
    }
}
