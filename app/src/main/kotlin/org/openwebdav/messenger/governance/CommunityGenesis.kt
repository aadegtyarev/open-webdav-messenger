package org.openwebdav.messenger.governance

data class CommunityGenesis(
    val communityId: String,
    val hostSigningPublicKey: ByteArray,
    val hostBoxPublicKey: ByteArray,
    val nonce: ByteArray,
    val signature: ByteArray,
)
