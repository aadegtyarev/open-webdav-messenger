package org.openwebdav.messenger.governance

import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.identity.IdentityCrypto
import org.openwebdav.messenger.transport.WebDavResult

internal class CommunityGroupOriginScanner(
    private val reader: CommunityGovernanceReader,
    private val crypto: IdentityCrypto,
) {
    suspend fun inspect(
        communityId: String,
        chatId: String,
        key: ChatKey,
    ): Result {
        val hashes = reader.listHashes(GovernanceObjectKind.GROUP_ORIGIN)
        if (hashes !is WebDavResult.Success) return Result.Unavailable
        var found: Result.Found? = null
        for (hash in hashes.value) {
            val fetched = reader.fetch(GovernanceObjectKind.GROUP_ORIGIN, communityId, hash, key)
            if (fetched !is WebDavResult.Success) return Result.Unavailable
            val origin = CommunityGroupOriginCodec.verify(fetched.value, null, null, crypto)
            val originHash = CommunityGroupOriginCodec.contentAddress(fetched.value)
            fetched.value.fill(0)
            if (origin == null || origin.communityId != communityId || origin.chatId != chatId) continue
            val candidate = Result.Found(origin, originHash)
            if (found != null && found.originHash != candidate.originHash) return Result.Conflict
            found = candidate
        }
        return found ?: Result.Empty
    }

    sealed interface Result {
        data class Found(val origin: CommunityGroupOrigin, val originHash: String) : Result

        data object Empty : Result

        data object Conflict : Result

        data object Unavailable : Result
    }
}
