package org.openwebdav.messenger.governance

import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.identity.IdentityCrypto
import org.openwebdav.messenger.transport.WebDavResult

internal class CommunityTombstoneScanner(
    private val reader: CommunityGovernanceReader,
    private val envelopes: CommunityGovernanceEnvelopeCodec,
    private val crypto: IdentityCrypto,
) {
    suspend fun find(
        expected: CommunityTombstone,
        expectedBytes: ByteArray,
        key: ChatKey,
    ): Result {
        val kind = kind(expected.scope)
        val listing = reader.listHashes(kind)
        if (listing !is WebDavResult.Success) return Result.Unavailable
        val expectedHash = CommunityTombstoneCodec.contentAddress(expectedBytes)
        var found: Result.Found? = null
        for (hash in listing.value) {
            val stored = reader.readStoredBytes(kind, hash)
            if (stored !is WebDavResult.Success) return Result.Unavailable
            val plaintext = envelopes.open(kind, expected.communityId, key, stored.value) ?: continue
            try {
                val record = CommunityTombstoneCodec.verifyAny(plaintext, crypto) ?: return Result.Conflict
                if (record.communityId != expected.communityId || record.scope != expected.scope) return Result.Conflict
                if (record.chatId != expected.chatId) continue
                val recordHash = CommunityTombstoneCodec.contentAddress(plaintext)
                if (recordHash != expectedHash) return Result.Conflict
                val previous = found
                if (previous != null && previous.recordHash != recordHash) return Result.Conflict
                if (previous == null) found = Result.Found(hash, recordHash)
            } finally {
                plaintext.fill(0)
            }
        }
        return found ?: Result.Empty
    }

    private fun kind(scope: TombstoneScope) =
        if (scope == TombstoneScope.GROUP) GovernanceObjectKind.GROUP_TOMBSTONE else GovernanceObjectKind.DM_TOMBSTONE

    sealed interface Result {
        data class Found(val objectHash: String, val recordHash: String) : Result

        data object Empty : Result

        data object Conflict : Result

        data object Unavailable : Result
    }
}
