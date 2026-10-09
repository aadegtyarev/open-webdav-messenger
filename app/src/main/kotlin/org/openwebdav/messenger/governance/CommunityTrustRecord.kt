package org.openwebdav.messenger.governance

data class CommunityTrustRecord(
    val communityId: String,
    val genesisHash: String,
    val hostSigningPublicKey: ByteArray,
    val hostBoxPublicKey: ByteArray,
    val fingerprintVerified: Boolean,
    val highestPolicyRevision: Long,
    val highestPolicyHash: String?,
)
