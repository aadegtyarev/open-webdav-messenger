package org.openwebdav.messenger.export

import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.governance.CommunityGovernanceEnvelopeCodec
import org.openwebdav.messenger.governance.CommunityTombstoneCodec
import org.openwebdav.messenger.governance.GovernanceObjectKind
import org.openwebdav.messenger.governance.TombstoneOriginMode
import org.openwebdav.messenger.governance.TombstoneScope
import org.openwebdav.messenger.identity.IdentityCrypto

internal object AccountBackupGraveyardValidation {
    fun isValid(deletions: List<ChatDeletionBackup>): Boolean =
        runCatching {
            require(deletions.size <= MAX_CHATS && deletions.map { it.chatId }.distinct().size == deletions.size)
            deletions.forEach(::validate)
        }.isSuccess

    fun validate(deletion: ChatDeletionBackup) {
        require(IDENTIFIER.matches(deletion.chatId))
        require(deletion.scope in SCOPES && deletion.originMode in ORIGIN_MODES && deletion.phase in PHASES)
        require(HASH.matches(deletion.originHash) && HASH.matches(deletion.tombstoneHash))
        require(deletion.cleanupAttempts in 0..MAX_ATTEMPTS && (deletion.lastError?.length ?: 0) <= MAX_ERROR_CHARS)
        val bytes = java.util.Base64.getDecoder().decode(deletion.tombstoneStoredBytesBase64)
        require(bytes.isNotEmpty() && bytes.size <= MAX_TOMBSTONE_BYTES)
        require(java.util.Base64.getEncoder().encodeToString(bytes) == deletion.tombstoneStoredBytesBase64)
    }

    fun hasAuthenticatedTombstones(
        communityId: String,
        communityKey: ChatKey?,
        deletions: List<ChatDeletionBackup>,
        envelopes: CommunityGovernanceEnvelopeCodec,
        identityCrypto: IdentityCrypto,
    ): Boolean =
        runCatching {
            if (deletions.isNotEmpty()) {
                val key = requireNotNull(communityKey)
                deletions.forEach { deletion ->
                    val stored = java.util.Base64.getDecoder().decode(deletion.tombstoneStoredBytesBase64)
                    val scope = TombstoneScope.valueOf(deletion.scope)
                    val objectKind =
                        if (scope == TombstoneScope.GROUP) {
                            GovernanceObjectKind.GROUP_TOMBSTONE
                        } else {
                            GovernanceObjectKind.DM_TOMBSTONE
                        }
                    val plaintext = requireNotNull(envelopes.open(objectKind, communityId, key, stored))
                    try {
                        val record = requireNotNull(CommunityTombstoneCodec.verifyAny(plaintext, identityCrypto))
                        require(record.communityId == communityId && record.chatId == deletion.chatId)
                        require(record.scope == scope && record.originMode == TombstoneOriginMode.valueOf(deletion.originMode))
                        require(record.originHash == deletion.originHash)
                        require(CommunityTombstoneCodec.contentAddress(plaintext) == deletion.tombstoneHash)
                    } finally {
                        plaintext.fill(0)
                    }
                }
            }
        }.isSuccess

    const val MAX_CHATS = 1000
    const val MAX_TOMBSTONE_BYTES = 64 * 1024

    private val IDENTIFIER = Regex("[A-Za-z0-9_-]{1,96}")
    private val HASH = Regex("[a-z2-7]{32}")
    private val SCOPES = setOf("GROUP", "DM")
    private val ORIGIN_MODES = setOf("ORIGIN_HASH", "LEGACY_CHAT_ID")
    val PHASES = setOf("LOCAL_COMMITTED", "TOMBSTONE_PUBLISHED", "CLEANUP_PENDING", "COMPLETE")
    const val MAX_ATTEMPTS = 1_000_000
    const val MAX_ERROR_CHARS = 1024
}
