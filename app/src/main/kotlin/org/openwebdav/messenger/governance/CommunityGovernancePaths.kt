package org.openwebdav.messenger.governance

internal object CommunityGovernancePaths {
    fun collectionPath(kind: GovernanceObjectKind): String = "governance/${SEGMENTS.getValue(kind)}"

    fun requiredCollections(kind: GovernanceObjectKind): List<String> {
        val segments = SEGMENTS.getValue(kind).split('/')
        return (0..segments.size).map { count -> (listOf("governance") + segments.take(count)).joinToString("/") }
    }

    fun objectPath(
        kind: GovernanceObjectKind,
        contentHash: String,
    ): String {
        require(isValidContentHash(contentHash)) { "invalid governance content hash" }
        return "${collectionPath(kind)}/$contentHash"
    }

    fun isValidContentHash(value: String): Boolean = value.length == HASH_CHARS && value.all { it in HASH_ALPHABET }

    private val SEGMENTS =
        mapOf(
            GovernanceObjectKind.GENESIS to "genesis",
            GovernanceObjectKind.POLICY to "policies",
            GovernanceObjectKind.GROUP_ORIGIN to "origins/group",
            GovernanceObjectKind.DM_ORIGIN to "origins/dm",
            GovernanceObjectKind.GROUP_TOMBSTONE to "tombstones/group",
            GovernanceObjectKind.DM_TOMBSTONE to "tombstones/dm",
        )
    private const val HASH_CHARS = 32
    private const val HASH_ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"
}
