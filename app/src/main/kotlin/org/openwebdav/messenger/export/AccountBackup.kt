package org.openwebdav.messenger.export

import org.openwebdav.messenger.transport.ConnectionConfig

/** Community membership and navigation data included with device-local secrets in a versioned backup. */
data class AccountBackup(
    val activeCommunityId: String,
    val communities: List<CommunityBackup>,
)

data class CommunityBackup(
    val id: String,
    val name: String,
    val anchorChatId: String,
    val config: ConnectionConfig,
    val chats: List<ChatBackup>,
    val communityKeyBase64: String? = null,
    val isHost: Boolean = false,
    val pollFloorSeconds: Int = 60,
    val retentionWindowDays: Int = 14,
)

data class ChatBackup(
    val id: String,
    val name: String,
    val kind: String,
    val access: String = "unknown",
)
