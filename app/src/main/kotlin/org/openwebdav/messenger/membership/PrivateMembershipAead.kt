package org.openwebdav.messenger.membership

import org.openwebdav.messenger.crypto.Aead
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.crypto.OpenResult
import org.openwebdav.messenger.protocol.Envelope

/** Private-claim AEAD binds canonical protocol domain, wire version, and exact chat ID as AAD. */
internal class PrivateMembershipAead(private val aead: Aead) {
    fun seal(
        chatId: String,
        key: ChatKey,
        plaintext: ByteArray,
    ): ByteArray = Envelope.frame(Envelope.CODEC_NONE, aead.sealWithAssociatedData(key, associatedData(chatId), plaintext))

    fun open(
        chatId: String,
        key: ChatKey,
        ciphertext: ByteArray,
    ): OpenResult {
        val frame = Envelope.readFrame(ciphertext) ?: return OpenResult.Rejected
        if (frame.codecId != Envelope.CODEC_NONE) return OpenResult.Rejected
        return aead.openWithAssociatedData(key, associatedData(chatId), frame.blob)
    }

    private fun associatedData(chatId: String): ByteArray {
        PrivateMembershipPaths.collection(chatId)
        val id = chatId.toByteArray(Charsets.UTF_8)
        require(id.size <= PrivateMembershipFormat.MAX_CHAT_ID_BYTES)
        return DOMAIN.toByteArray(Charsets.UTF_8) + byteArrayOf(0, PrivateMembershipFormat.VERSION) +
            byteArrayOf((id.size ushr 8).toByte(), id.size.toByte()) + id
    }

    private companion object {
        const val DOMAIN = "owdm/private-membership/aead"
    }
}
