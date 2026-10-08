package org.openwebdav.messenger.app

import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.identity.Identity
import java.nio.ByteBuffer
import java.security.MessageDigest

internal object RosterCacheProvenance {
    private const val DOMAIN = "open-webdav-messenger/verified-roster-cache/v1"

    fun digest(
        communityId: String,
        chatId: String,
        communityKey: ChatKey,
        chatKey: ChatKey,
        identity: Identity,
    ): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val keys = listOf(communityKey.copyBytes(), chatKey.copyBytes())
        val fields =
            listOf(
                DOMAIN.toByteArray(Charsets.UTF_8),
                communityId.toByteArray(Charsets.UTF_8),
                chatId.toByteArray(Charsets.UTF_8),
                *keys.toTypedArray(),
                identity.copySignPublic(),
                identity.copyBoxPublic(),
            )
        fields.forEach { bytes ->
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            digest.update(bytes)
        }
        keys.forEach { it.fill(0) }
        return digest.digest()
    }
}
