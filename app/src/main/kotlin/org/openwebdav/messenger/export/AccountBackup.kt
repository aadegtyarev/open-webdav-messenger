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
    val hiddenDmChatIds: List<String> = emptyList(),
    val suppressedDmMessageIds: Map<String, List<String>> = emptyMap(),
    val genesisRecordBase64: String? = null,
    val hostFingerprintVerified: Boolean = false,
    val highestPolicyRevision: Long = 0,
    val highestPolicyHash: String? = null,
)

data class CommunityTrustBackup(
    val genesisRecordBase64: String,
    val fingerprintVerified: Boolean,
    val highestPolicyRevision: Long,
    val highestPolicyHash: String?,
)

data class ChatBackup(
    val id: String,
    val name: String,
    val kind: String,
    val access: String = "unknown",
)
