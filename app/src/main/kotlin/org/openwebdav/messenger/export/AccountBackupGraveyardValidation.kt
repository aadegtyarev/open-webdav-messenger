package org.openwebdav.messenger.export

import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.governance.CommunityDmOriginCodec
import org.openwebdav.messenger.governance.CommunityDmOriginParticipants
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
        listOf(deletion.tombstonePlaintextBytesBase64, deletion.originPlaintextBytesBase64).forEach { encoded ->
            if (encoded != null) {
                val evidence = java.util.Base64.getDecoder().decode(encoded)
                require(evidence.isNotEmpty() && evidence.size <= MAX_TOMBSTONE_BYTES)
                require(java.util.Base64.getEncoder().encodeToString(evidence) == encoded)
            }
        }
    }

    fun hasAuthenticatedTombstones(
        communityId: String,
        communityKey: ChatKey?,
        deletions: List<ChatDeletionBackup>,
        envelopes: CommunityGovernanceEnvelopeCodec,
        identityCrypto: IdentityCrypto,
        expectedSignerPublicKey: ByteArray? = null,
    ): Boolean =
        runCatching {
            if (deletions.isNotEmpty()) {
                deletions.forEach { deletion ->
                    val scope = TombstoneScope.valueOf(deletion.scope)
                    val signed =
                        if (scope == TombstoneScope.DM) {
                            val encoded = requireNotNull(deletion.tombstonePlaintextBytesBase64)
                            java.util.Base64.getDecoder().decode(encoded)
                        } else {
                            val key = requireNotNull(communityKey)
                            val stored = java.util.Base64.getDecoder().decode(deletion.tombstoneStoredBytesBase64)
                            requireNotNull(
                                envelopes.open(GovernanceObjectKind.GROUP_TOMBSTONE, communityId, key, stored),
                            )
                        }
                    try {
                        val record = requireNotNull(CommunityTombstoneCodec.verifyAny(signed, identityCrypto))
                        require(record.communityId == communityId && record.chatId == deletion.chatId)
                        require(record.scope == scope && record.originMode == TombstoneOriginMode.valueOf(deletion.originMode))
                        require(record.originHash == deletion.originHash)
                        require(CommunityTombstoneCodec.contentAddress(signed) == deletion.tombstoneHash)
                        if (scope == TombstoneScope.DM) {
                            val originBytes =
                                java.util.Base64.getDecoder().decode(requireNotNull(deletion.originPlaintextBytesBase64))
                            try {
                                val origin =
                                    requireNotNull(
                                        CommunityDmOriginCodec.verify(
                                            originBytes,
                                            communityId,
                                            deletion.chatId,
                                            identityCrypto,
                                        ),
                                    )
                                require(CommunityDmOriginCodec.contentAddress(originBytes) == deletion.originHash)
                                require(record.signerRole.name == "DM_INITIATOR")
                                require(record.originMode == TombstoneOriginMode.ORIGIN_HASH)
                                require(record.signerSigningPublicKey.contentEquals(origin.initiatorSigningPublicKey))
                                require(CommunityDmOriginParticipants.matches(record.participants, origin.participants))
                                expectedSignerPublicKey?.let { expectedSigner ->
                                    require(
                                        origin.participants.any { participant ->
                                            participant.signingPublicKey.contentEquals(expectedSigner)
                                        },
                                    )
                                }
                            } finally {
                                originBytes.fill(0)
                            }
                        }
                    } finally {
                        signed.fill(0)
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
