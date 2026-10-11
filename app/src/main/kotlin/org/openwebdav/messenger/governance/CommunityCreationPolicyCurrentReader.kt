package org.openwebdav.messenger.governance

import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.identity.IdentityCrypto
import org.openwebdav.messenger.transport.WebDavResult

internal class CommunityCreationPolicyCurrentReader(
    private val reader: CommunityGovernanceReader,
    private val communityId: String,
    private val key: ChatKey,
    private val hostSigningKey: ByteArray,
    private val highestRevision: Long,
    private val highestHash: String?,
    private val crypto: IdentityCrypto,
    private val acceptRevision: suspend (Long, String) -> PolicyRevisionDecision,
) {
    suspend fun read(): Result {
        val listing = reader.listHashes(GovernanceObjectKind.POLICY)
        if (listing !is WebDavResult.Success) return Result.Unavailable
        if (listing.value.isEmpty()) return if (highestRevision == 0L) Result.Policy(null) else Result.Conflict
        val records = mutableListOf<Pair<String, CommunityCreationPolicy>>()
        for (hash in listing.value) {
            val fetched = reader.fetch(GovernanceObjectKind.POLICY, communityId, hash, key)
            if (fetched !is WebDavResult.Success) return Result.Unavailable
            val policy =
                CommunityCreationPolicyCodec.verify(fetched.value, communityId, hostSigningKey, crypto)
                    ?: return Result.Conflict
            fetched.value.fill(0)
            records += hash to policy
        }
        val revision = records.maxOf { it.second.revision }
        val newest = records.filter { it.second.revision == revision }
        if (newest.map { it.first }.distinct().size != 1) return Result.Conflict
        val (hash, policy) = newest.single()
        if (revision < highestRevision || (revision == highestRevision && highestHash != null && hash != highestHash)) {
            return Result.Conflict
        }
        if (revision > highestRevision) {
            val decision = acceptRevision(revision, hash)
            if (decision !in setOf(PolicyRevisionDecision.Accepted, PolicyRevisionDecision.Duplicate)) {
                return Result.Conflict
            }
        }
        return Result.Policy(policy)
    }

    sealed interface Result {
        data class Policy(val value: CommunityCreationPolicy?) : Result

        data object Unavailable : Result

        data object Conflict : Result
    }
}
