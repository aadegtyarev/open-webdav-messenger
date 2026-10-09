package org.openwebdav.messenger.governance

import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.identity.IdentityCrypto
import org.openwebdav.messenger.transport.WebDavResult

/** Bounded governance scan for a tombstone scoped to one exact community/chat pair. */
internal class CommunityTombstoneChatScanner(
    private val reader: CommunityGovernanceReader,
    private val envelopes: CommunityGovernanceEnvelopeCodec,
    private val crypto: IdentityCrypto,
) {
    suspend fun scan(
        communityId: String,
        chatId: String,
        scope: TombstoneScope,
        key: ChatKey,
    ): Result {
        val kind = if (scope == TombstoneScope.GROUP) GovernanceObjectKind.GROUP_TOMBSTONE else GovernanceObjectKind.DM_TOMBSTONE
        val listing = reader.listHashes(kind)
        if (listing !is WebDavResult.Success) return Result.Unavailable
        var found: Result.Found? = null
        for (hash in listing.value) {
            val stored = reader.readStoredBytes(kind, hash)
            if (stored !is WebDavResult.Success) return Result.Unavailable
            val plaintext = envelopes.open(kind, communityId, key, stored.value)
            if (plaintext == null) {
                stored.value.fill(0)
                continue
            }
            try {
                val record = CommunityTombstoneCodec.verifyAny(plaintext, crypto) ?: continue
                if (record.communityId != communityId || record.scope != scope) return Result.Conflict
                if (record.chatId != chatId) continue
                val recordHash = CommunityTombstoneCodec.contentAddress(plaintext)
                if (found != null && found.recordHash != recordHash) return Result.Conflict
                if (found == null) found = Result.Found(record, recordHash, plaintext.copyOf(), stored.value.copyOf())
            } finally {
                plaintext.fill(0)
                stored.value.fill(0)
            }
        }
        return found ?: Result.Empty
    }

    sealed interface Result {
        data class Found(
            val record: CommunityTombstone,
            val recordHash: String,
            val signedBytes: ByteArray,
            val storedBytes: ByteArray,
        ) : Result

        data object Empty : Result

        data object Conflict : Result

        data object Unavailable : Result
    }
}
