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
        authorized: AuthorizedCommunityTombstone,
        signingSecret: ByteArray,
        key: ChatKey,
    ): Result {
        val record = authorized.record
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

    suspend fun publishStored(
        record: CommunityTombstone,
        signedBytes: ByteArray,
        storedBytes: ByteArray,
        key: ChatKey,
    ): Result {
        val verified = CommunityTombstoneCodec.verifyAny(signedBytes, crypto) ?: return Result.Invalid
        if (verified.communityId != record.communityId || verified.chatId != record.chatId ||
            verified.originHash != record.originHash || verified.scope != record.scope ||
            verified.originMode != record.originMode || verified.revision != record.revision || verified.signerRole != record.signerRole ||
            !verified.signerSigningPublicKey.contentEquals(record.signerSigningPublicKey) ||
            (record.scope == TombstoneScope.GROUP && (verified.participants.isNotEmpty() || record.participants.isNotEmpty())) ||
            (record.scope == TombstoneScope.DM && !CommunityDmOriginParticipants.matches(verified.participants, record.participants))
        ) {
            return Result.Invalid
        }
        val kind = if (record.scope == TombstoneScope.GROUP) GovernanceObjectKind.GROUP_TOMBSTONE else GovernanceObjectKind.DM_TOMBSTONE
        val opened = envelopes.open(kind, record.communityId, key, storedBytes) ?: return Result.Invalid
        if (!opened.contentEquals(signedBytes)) return Result.Invalid
        val reference = GovernanceObjectReference(kind, record.communityId, envelopes.contentHash(storedBytes), storedBytes)
        when (scanner.find(record, signedBytes, key)) {
            is CommunityTombstoneScanner.Result.Found -> return Result.AlreadyPresent
            CommunityTombstoneScanner.Result.Conflict -> return Result.Conflict
            CommunityTombstoneScanner.Result.Unavailable -> return Result.Unavailable
            CommunityTombstoneScanner.Result.Empty -> Unit
        }
        if (writer.publish(reference) !is WebDavResult.Success) return Result.Unavailable
        return when (scanner.find(record, signedBytes, key)) {
            is CommunityTombstoneScanner.Result.Found -> Result.Published(CommunityTombstoneCodec.contentAddress(signedBytes))
            CommunityTombstoneScanner.Result.Conflict -> Result.Conflict
            else -> Result.Unavailable
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
