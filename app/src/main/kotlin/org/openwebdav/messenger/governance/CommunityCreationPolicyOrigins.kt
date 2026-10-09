package org.openwebdav.messenger.governance

import org.openwebdav.messenger.chatdirectory.ChatAccess
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.identity.IdentityCrypto
import org.openwebdav.messenger.transport.WebDavResult

internal class CommunityCreationPolicyOrigins(
    private val reader: CommunityGovernanceReader,
    private val communityId: String,
    private val key: ChatKey,
    private val crypto: IdentityCrypto,
) {
    suspend fun snapshot(
        allowPublic: Boolean,
        allowPrivate: Boolean,
        previous: List<String>,
    ): Result {
        val listed = reader.listHashes(GovernanceObjectKind.GROUP_ORIGIN)
        if (listed !is WebDavResult.Success) return Result.Unavailable
        val hashes = previous.toMutableSet()
        val chats = mutableMapOf<String, String>()
        for (hash in listed.value) {
            val fetched = reader.fetch(GovernanceObjectKind.GROUP_ORIGIN, communityId, hash, key)
            if (fetched !is WebDavResult.Success) return Result.Unavailable
            val origin =
                CommunityGroupOriginCodec.verify(fetched.value, communityId, null, crypto)
                    ?: return Result.Conflict
            val actualHash = CommunityGroupOriginCodec.contentAddress(fetched.value)
            fetched.value.fill(0)
            val old = chats.putIfAbsent(origin.chatId, actualHash)
            if (old != null && old != actualHash) return Result.Conflict
            val disabledType =
                (origin.access == ChatAccess.PUBLIC && !allowPublic) ||
                    (origin.access == ChatAccess.PRIVATE && !allowPrivate)
            if (disabledType) hashes.add(actualHash)
            if (hashes.size > MAX_GRANDFATHERED_ORIGINS) return Result.TooManyOrigins
        }
        return Result.Snapshot(hashes.sorted())
    }

    sealed interface Result {
        data class Snapshot(val hashes: List<String>) : Result

        data object Unavailable : Result

        data object Conflict : Result

        data object TooManyOrigins : Result
    }

    private companion object {
        const val MAX_GRANDFATHERED_ORIGINS = 1024
    }
}
