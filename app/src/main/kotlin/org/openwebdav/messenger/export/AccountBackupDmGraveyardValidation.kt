package org.openwebdav.messenger.export

import org.openwebdav.messenger.governance.CommunityDmOriginCodec
import org.openwebdav.messenger.governance.CommunityDmOriginParticipants
import org.openwebdav.messenger.governance.CommunityDmParticipant
import org.openwebdav.messenger.governance.CommunityTombstone
import org.openwebdav.messenger.governance.TombstoneOriginMode
import org.openwebdav.messenger.identity.IdentityCrypto

internal object AccountBackupDmGraveyardValidation {
    fun validateEvidence(
        deletion: ChatDeletionBackup,
        record: CommunityTombstone,
        communityId: String,
        identityCrypto: IdentityCrypto,
        expectedOwner: CommunityDmParticipant?,
    ) {
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
            expectedOwner?.let { owner ->
                require(
                    origin.participants.any { participant ->
                        participant.signingPublicKey.contentEquals(owner.signingPublicKey) &&
                            participant.boxPublicKey.contentEquals(owner.boxPublicKey)
                    },
                )
            }
        } finally {
            originBytes.fill(0)
        }
    }
}
