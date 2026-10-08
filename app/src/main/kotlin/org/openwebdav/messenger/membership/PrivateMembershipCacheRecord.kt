package org.openwebdav.messenger.membership

/** Encrypted device-local snapshot; never exported and never treated as remote authority. */
internal data class PrivateMembershipCacheRecord(
    val communityId: String,
    val chatId: String,
    val provenance: ByteArray,
    val members: List<PrivateMembershipCacheMember>,
)

internal data class PrivateMembershipCacheMember(
    val displayName: String,
    val signingPublicKey: ByteArray,
    val boxPublicKey: ByteArray,
    val directoryVerified: Boolean,
)
