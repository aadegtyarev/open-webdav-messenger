package org.openwebdav.messenger.app

import org.openwebdav.messenger.membership.MembershipIdentityProvenance
import org.openwebdav.messenger.membership.PrivateMembershipCacheMember
import org.openwebdav.messenger.membership.PrivateMembershipCacheRecord
import org.openwebdav.messenger.membership.PrivateMembershipClaim
import org.openwebdav.messenger.membership.VerifiedPrivateMember

internal object PrivateMembershipCacheProjection {
    fun record(
        communityId: String,
        chatId: String,
        provenance: ByteArray,
        members: List<VerifiedPrivateMember>,
    ) = PrivateMembershipCacheRecord(
        communityId,
        chatId,
        provenance.copyOf(),
        members.map { member ->
            PrivateMembershipCacheMember(
                member.displayName,
                member.claim.copySigningPublicKey(),
                member.claim.copyBoxPublicKey(),
                member.provenance == MembershipIdentityProvenance.COMMUNITY_DIRECTORY,
            )
        },
    )

    fun members(
        chatId: String,
        cached: List<PrivateMembershipCacheMember>,
    ) = cached.map { member ->
        VerifiedPrivateMember(
            PrivateMembershipClaim(chatId, member.displayName, member.signingPublicKey, member.boxPublicKey),
            member.displayName,
            if (member.directoryVerified) {
                MembershipIdentityProvenance.COMMUNITY_DIRECTORY
            } else {
                MembershipIdentityProvenance.PRIVATE_CHAT_ONLY
            },
        )
    }
}
