package org.openwebdav.messenger.governance

import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.identity.IdentityCrypto
import org.openwebdav.messenger.transport.WebDavResult

internal class CommunityCreationPolicyAdministration(
    private val reader: CommunityGovernanceReader,
    private val writer: CommunityGovernanceWriter,
    private val communityId: String,
    private val key: ChatKey,
    private val hostSigningKey: ByteArray,
    private val signingSecret: ByteArray,
    private val highestRevision: Long,
    private val highestHash: String?,
    private val crypto: IdentityCrypto,
    private val acceptRevision: (Long, String) -> PolicyRevisionDecision,
) {
    suspend fun update(
        allowPublic: Boolean,
        allowPrivate: Boolean,
    ): Result {
        val current =
            CommunityCreationPolicyCurrentReader(
                reader,
                communityId,
                key,
                hostSigningKey,
                highestRevision,
                highestHash,
                crypto,
                acceptRevision,
            ).read()
        val currentPolicy =
            when (current) {
                is CommunityCreationPolicyCurrentReader.Result.Policy -> current.value
                CommunityCreationPolicyCurrentReader.Result.Unavailable -> return Result.Unavailable
                CommunityCreationPolicyCurrentReader.Result.Conflict -> return Result.Conflict
            }
        val revision = maxOf(highestRevision, currentPolicy?.revision ?: 0L) + 1
        val previous = currentPolicy?.grandfatheredOriginHashes.orEmpty()
        val snapshot =
            if (allowPublic && allowPrivate) {
                previous
            } else {
                when (
                    val scan =
                        CommunityCreationPolicyOrigins(reader, communityId, key, crypto)
                            .snapshot(allowPublic, allowPrivate, previous)
                ) {
                    is CommunityCreationPolicyOrigins.Result.Snapshot -> scan.hashes
                    CommunityCreationPolicyOrigins.Result.Unavailable -> return Result.Unavailable
                    CommunityCreationPolicyOrigins.Result.Conflict -> return Result.Conflict
                    CommunityCreationPolicyOrigins.Result.TooManyOrigins -> return Result.TooManyOrigins
                }
            }
        val signed =
            CommunityCreationPolicyCodec.sign(
                communityId,
                revision,
                allowPublic,
                allowPrivate,
                snapshot,
                signingSecret,
                crypto,
            )
        return try {
            val reference = writer.seal(GovernanceObjectKind.POLICY, communityId, key, signed)
            if (writer.publish(reference) !is WebDavResult.Success) return Result.Unavailable
            when (acceptRevision(revision, reference.contentHash)) {
                PolicyRevisionDecision.Accepted, PolicyRevisionDecision.Duplicate ->
                    Result.Published(CommunityCreationPolicyCodec.verify(signed, communityId, hostSigningKey, crypto)!!)
                else -> Result.Conflict
            }
        } finally {
            signed.fill(0)
        }
    }

    sealed interface Result {
        data class Published(val policy: CommunityCreationPolicy) : Result

        data object Unavailable : Result

        data object Conflict : Result

        data object TooManyOrigins : Result
    }
}
