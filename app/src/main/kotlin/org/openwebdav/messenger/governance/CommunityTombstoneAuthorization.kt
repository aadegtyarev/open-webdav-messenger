package org.openwebdav.messenger.governance

internal object CommunityTombstoneAuthorization {
    fun groupUnderOriginConflict(
        tombstone: CommunityTombstone,
        hostPin: CommunityTrustRecord?,
    ): AuthorizedCommunityTombstone? {
        if (tombstone.originMode != TombstoneOriginMode.LEGACY_CHAT_ID || tombstone.signerRole != TombstoneSignerRole.PINNED_HOST) {
            return null
        }
        return group(tombstone, null, null, hostPin)
    }

    fun group(
        tombstone: CommunityTombstone,
        origin: CommunityGroupOrigin?,
        originHash: String?,
        hostPin: CommunityTrustRecord?,
    ): AuthorizedCommunityTombstone? {
        if (tombstone.scope != TombstoneScope.GROUP || tombstone.participants.isNotEmpty()) return null
        val authorized =
            when (tombstone.signerRole) {
                TombstoneSignerRole.PINNED_HOST ->
                    hostPin?.let {
                        it.fingerprintVerified && it.communityId == tombstone.communityId &&
                            it.hostSigningPublicKey.contentEquals(tombstone.signerSigningPublicKey) &&
                            (
                                tombstone.originMode == TombstoneOriginMode.ORIGIN_HASH ||
                                    tombstone.originHash ==
                                    LegacyChatOriginReference.contentAddress(
                                        tombstone.communityId,
                                        tombstone.chatId,
                                    )
                            )
                    } ?: false
                TombstoneSignerRole.GROUP_CREATOR ->
                    tombstone.originMode == TombstoneOriginMode.ORIGIN_HASH && origin != null &&
                        origin.communityId == tombstone.communityId && origin.chatId == tombstone.chatId &&
                        originHash == tombstone.originHash && origin.creatorSigningPublicKey.contentEquals(tombstone.signerSigningPublicKey)
                TombstoneSignerRole.DM_INITIATOR -> false
            }
        return AuthorizedCommunityTombstone(tombstone).takeIf { authorized }
    }

    fun dm(
        tombstone: CommunityTombstone,
        origin: CommunityDmOrigin,
        originHash: String,
    ): AuthorizedCommunityTombstone? {
        val authorized =
            tombstone.scope == TombstoneScope.DM && tombstone.originMode == TombstoneOriginMode.ORIGIN_HASH &&
                tombstone.signerRole == TombstoneSignerRole.DM_INITIATOR &&
                tombstone.communityId == origin.communityId && tombstone.chatId == origin.chatId &&
                tombstone.originHash == originHash && tombstone.signerSigningPublicKey.contentEquals(origin.initiatorSigningPublicKey) &&
                CommunityDmOriginParticipants.matches(tombstone.participants, origin.participants)
        return AuthorizedCommunityTombstone(tombstone).takeIf { authorized }
    }
}
