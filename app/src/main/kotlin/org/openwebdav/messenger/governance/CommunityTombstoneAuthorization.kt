package org.openwebdav.messenger.governance

internal object CommunityTombstoneAuthorization {
    fun group(
        tombstone: CommunityTombstone,
        origin: CommunityGroupOrigin?,
        originHash: String?,
        hostPin: CommunityTrustRecord?,
    ): Boolean {
        if (tombstone.scope != TombstoneScope.GROUP || tombstone.participants.isNotEmpty()) return false
        return when (tombstone.signerRole) {
            TombstoneSignerRole.PINNED_HOST ->
                hostPin?.let {
                    it.fingerprintVerified && it.communityId == tombstone.communityId &&
                        it.hostSigningPublicKey.contentEquals(tombstone.signerSigningPublicKey)
                } ?: false
            TombstoneSignerRole.GROUP_CREATOR ->
                origin != null && origin.communityId == tombstone.communityId && origin.chatId == tombstone.chatId &&
                    originHash == tombstone.originHash && origin.creatorSigningPublicKey.contentEquals(tombstone.signerSigningPublicKey)
            TombstoneSignerRole.DM_INITIATOR -> false
        }
    }

    fun dm(
        tombstone: CommunityTombstone,
        origin: CommunityDmOrigin,
        originHash: String,
    ): Boolean =
        tombstone.scope == TombstoneScope.DM && tombstone.signerRole == TombstoneSignerRole.DM_INITIATOR &&
            tombstone.communityId == origin.communityId && tombstone.chatId == origin.chatId &&
            tombstone.originHash == originHash && tombstone.signerSigningPublicKey.contentEquals(origin.initiatorSigningPublicKey) &&
            CommunityDmOriginParticipants.matches(tombstone.participants, origin.participants)
}
