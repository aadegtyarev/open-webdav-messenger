package org.openwebdav.messenger.governance

data class GovernanceObjectReference(
    val kind: GovernanceObjectKind,
    val communityId: String,
    val contentHash: String,
    val storedBytes: ByteArray,
)
