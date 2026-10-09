package org.openwebdav.messenger.governance

data class CommunityDmParticipant(
    val signingPublicKey: ByteArray,
    val boxPublicKey: ByteArray,
)

data class CommunityDmOrigin(
    val communityId: String,
    val chatId: String,
    val participants: List<CommunityDmParticipant>,
    val initiatorSigningPublicKey: ByteArray,
    val nonce: ByteArray,
)
