package org.openwebdav.messenger.governance

import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.identity.IdentityCrypto
import org.openwebdav.messenger.transport.WebDavResult

internal class CommunityDmOriginScanner(
    private val reader: CommunityGovernanceReader,
    private val envelopes: CommunityGovernanceEnvelopeCodec,
    private val crypto: IdentityCrypto,
) {
    suspend fun inspect(
        communityId: String,
        chatId: String,
        participants: List<CommunityDmParticipant>,
        dmKey: ChatKey,
    ): Result {
        val listing = reader.listHashes(GovernanceObjectKind.DM_ORIGIN)
        if (listing !is WebDavResult.Success) return Result.Unavailable
        var objectHash: String? = null
        var recordHash: String? = null
        for (hash in listing.value) {
            val stored = reader.readStoredBytes(GovernanceObjectKind.DM_ORIGIN, hash)
            if (stored !is WebDavResult.Success) return Result.Unavailable
            val plaintext = envelopes.open(GovernanceObjectKind.DM_ORIGIN, communityId, dmKey, stored.value) ?: continue
            try {
                val origin = CommunityDmOriginCodec.verify(plaintext, communityId, chatId, crypto) ?: return Result.Conflict
                if (!CommunityDmOriginParticipants.matches(origin.participants, participants)) return Result.Conflict
                val candidateHash = CommunityDmOriginCodec.contentAddress(plaintext)
                if (recordHash != null && recordHash != candidateHash) return Result.Conflict
                if (recordHash == null) {
                    recordHash = candidateHash
                    objectHash = hash
                }
            } finally {
                plaintext.fill(0)
            }
        }
        return objectHash?.let { Result.Found(it, checkNotNull(recordHash)) } ?: Result.Empty
    }

    sealed interface Result {
        data class Found(val objectHash: String, val recordHash: String) : Result

        data object Empty : Result

        data object Conflict : Result

        data object Unavailable : Result
    }
}
