package org.openwebdav.messenger.app

import org.openwebdav.messenger.membership.MembershipIdentityProvenance
import org.openwebdav.messenger.membership.VerifiedPrivateMember
import org.openwebdav.messenger.protocol.Hex

internal fun privateVerifiedParticipants(
    members: List<VerifiedPrivateMember>,
    senderIdentifier: String,
    senderSigningPublicKey: ByteArray,
): List<VerifiedParticipant> {
    val participants =
        members.map { member ->
            val signingKey = member.claim.copySigningPublicKey()
            VerifiedParticipant(
                displayName = member.displayName,
                identityDigest = participantDigest(signingKey),
                isSelf = Hex.encode(signingKey) == senderIdentifier,
                provenance =
                    if (member.provenance == MembershipIdentityProvenance.COMMUNITY_DIRECTORY) {
                        ParticipantIdentityProvenance.COMMUNITY_DIRECTORY
                    } else {
                        ParticipantIdentityProvenance.PRIVATE_CHAT_ONLY
                    },
            )
        }
    return withSelfParticipant(
        participants,
        senderSigningPublicKey,
        ParticipantIdentityProvenance.PRIVATE_CHAT_ONLY,
    )
}
