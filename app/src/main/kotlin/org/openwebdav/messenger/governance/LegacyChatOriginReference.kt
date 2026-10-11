package org.openwebdav.messenger.governance

import org.openwebdav.messenger.protocol.Base32
import java.security.MessageDigest

internal object LegacyChatOriginReference {
    fun contentAddress(
        communityId: String,
        chatId: String,
    ): String {
        require(GovernanceRecordSupport.isValidCommunityId(communityId) && CHAT_ID.matches(chatId))
        val community = communityId.toByteArray(Charsets.UTF_8)
        val chat = chatId.toByteArray(Charsets.UTF_8)
        val input =
            byteArrayOf(VERSION) + DOMAIN + byteArrayOf(0) + lengthPrefix(community.size) + community +
                lengthPrefix(chat.size) + chat
        return Base32.encodeBase32Lower(MessageDigest.getInstance("SHA-256").digest(input)).take(HASH_CHARS)
    }

    private fun lengthPrefix(size: Int) = byteArrayOf((size shr 8).toByte(), size.toByte())

    private val DOMAIN = "owdm/governance/legacy-chat-origin".toByteArray(Charsets.UTF_8)
    private val CHAT_ID = Regex("[A-Za-z0-9_-]{1,96}")
    private const val VERSION: Byte = 1
    private const val HASH_CHARS = 32
}
