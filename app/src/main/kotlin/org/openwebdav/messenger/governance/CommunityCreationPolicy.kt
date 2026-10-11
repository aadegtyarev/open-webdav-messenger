package org.openwebdav.messenger.governance

data class CommunityCreationPolicy(
    val communityId: String,
    val revision: Long,
    val allowPublic: Boolean,
    val allowPrivate: Boolean,
    val grandfatheredOriginHashes: List<String>,
    val signature: ByteArray,
)
