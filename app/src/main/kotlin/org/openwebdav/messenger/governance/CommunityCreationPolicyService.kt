package org.openwebdav.messenger.governance

import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.identity.IdentityCrypto
import org.openwebdav.messenger.transport.WebDavResult

internal class CommunityCreationPolicyService(
    private val reader: CommunityGovernanceReader,
    private val communityId: String,
    private val communityKey: ChatKey,
    private val hostSigningKey: ByteArray,
    private val hadTrustedPolicy: Boolean,
    private val crypto: IdentityCrypto,
    private val acceptRevision: (Long, String) -> PolicyRevisionDecision,
) {
    suspend fun read(): Result {
        val listing = reader.listHashes(GovernanceObjectKind.POLICY)
        if (listing !is WebDavResult.Success) return unavailable()
        if (listing.value.isEmpty()) return unavailable()
        val policies = mutableListOf<Pair<String, CommunityCreationPolicy>>()
        for (hash in listing.value) {
            val fetched = reader.fetch(GovernanceObjectKind.POLICY, communityId, hash, communityKey)
            if (fetched !is WebDavResult.Success) return unavailable()
            val policy = CommunityCreationPolicyCodec.verify(fetched.value, communityId, hostSigningKey, crypto)
            if (policy == null) {
                if (hadTrustedPolicy) return Result.Blocked
            } else {
                policies += hash to policy
            }
        }
        if (policies.isEmpty()) return unavailable()
        val newestRevision = policies.maxOf { it.second.revision }
        val newest = policies.filter { it.second.revision == newestRevision }
        if (newest.map { it.first }.distinct().size != 1) return Result.Blocked
        val (hash, policy) = newest.single()
        return when (acceptRevision(policy.revision, hash)) {
            PolicyRevisionDecision.Accepted, PolicyRevisionDecision.Duplicate -> Result.Policy(policy)
            PolicyRevisionDecision.UntrustedHost -> if (hadTrustedPolicy) Result.Blocked else Result.DefaultAllowed
            else -> Result.Blocked
        }
    }

    private fun unavailable(): Result = if (hadTrustedPolicy) Result.Blocked else Result.DefaultAllowed

    sealed interface Result {
        data object DefaultAllowed : Result

        data class Policy(val value: CommunityCreationPolicy) : Result

        data object Blocked : Result
    }
}
