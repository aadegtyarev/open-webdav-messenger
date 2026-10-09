package org.openwebdav.messenger.governance

import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.identity.IdentityCrypto
import org.openwebdav.messenger.transport.WebDavResult

internal class CommunityTombstoneService(
    private val reader: CommunityGovernanceReader,
    private val writer: CommunityGovernanceWriter,
    private val envelopes: CommunityGovernanceEnvelopeCodec,
    private val crypto: IdentityCrypto,
) {
    private val scanner = CommunityTombstoneScanner(reader, envelopes, crypto)

    suspend fun publish(
        record: CommunityTombstone,
        signingSecret: ByteArray,
        key: ChatKey,
    ): Result {
        val signedBytes =
            runCatching { CommunityTombstoneCodec.sign(record, signingSecret, crypto) }.getOrNull()
                ?: return Result.Invalid
        return try {
            when (scanner.find(record, signedBytes, key)) {
                is CommunityTombstoneScanner.Result.Found -> return Result.AlreadyPresent
                CommunityTombstoneScanner.Result.Conflict -> return Result.Conflict
                CommunityTombstoneScanner.Result.Unavailable -> return Result.Unavailable
                CommunityTombstoneScanner.Result.Empty -> Unit
            }
            val kind = if (record.scope == TombstoneScope.GROUP) GovernanceObjectKind.GROUP_TOMBSTONE else GovernanceObjectKind.DM_TOMBSTONE
            val reference = writer.seal(kind, record.communityId, key, signedBytes)
            if (writer.publish(reference) !is WebDavResult.Success) return Result.Unavailable
            when (scanner.find(record, signedBytes, key)) {
                is CommunityTombstoneScanner.Result.Found -> Result.Published(reference.contentHash)
                CommunityTombstoneScanner.Result.Conflict -> Result.Conflict
                else -> Result.Unavailable
            }
        } finally {
            signedBytes.fill(0)
        }
    }

    sealed interface Result {
        data class Published(val contentHash: String) : Result

        data object AlreadyPresent : Result

        data object Conflict : Result

        data object Unavailable : Result

        data object Invalid : Result
    }
}
