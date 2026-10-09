package org.openwebdav.messenger.governance

import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.identity.Identity
import org.openwebdav.messenger.identity.IdentityCrypto
import org.openwebdav.messenger.transport.WebDavResult
import java.security.SecureRandom

internal class CommunityDmOriginService(
    private val reader: CommunityGovernanceReader,
    private val writer: CommunityGovernanceWriter,
    private val envelopes: CommunityGovernanceEnvelopeCodec,
    private val crypto: IdentityCrypto,
) {
    private val scanner = CommunityDmOriginScanner(reader, envelopes, crypto)

    suspend fun ensureOrPublish(
        communityId: String,
        chatId: String,
        participants: List<CommunityDmParticipant>,
        initiator: Identity,
        dmKey: ChatKey,
    ): Result {
        when (val existing = scanner.inspect(communityId, chatId, participants, dmKey)) {
            is CommunityDmOriginScanner.Result.Found -> return Result.Ready(existing.objectHash, published = false)
            CommunityDmOriginScanner.Result.Empty -> Unit
            CommunityDmOriginScanner.Result.Conflict -> return Result.Conflict
            CommunityDmOriginScanner.Result.Unavailable -> return Result.Unavailable
        }

        val nonce = ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes)
        val secret = initiator.copySignSecret()
        val plaintext =
            try {
                CommunityDmOriginCodec.sign(
                    CommunityDmOrigin(communityId, chatId, participants, initiator.copySignPublic(), nonce),
                    secret,
                    crypto,
                )
            } finally {
                nonce.fill(0)
                secret.fill(0)
            }
        return try {
            val recordHash = CommunityDmOriginCodec.contentAddress(plaintext)
            val reference = writer.seal(GovernanceObjectKind.DM_ORIGIN, communityId, dmKey, plaintext)
            if (writer.publish(reference) !is WebDavResult.Success) return Result.Unavailable
            when (val observed = scanner.inspect(communityId, chatId, participants, dmKey)) {
                is CommunityDmOriginScanner.Result.Found ->
                    if (observed.recordHash == recordHash) Result.Ready(observed.objectHash, published = true) else Result.Conflict
                CommunityDmOriginScanner.Result.Conflict -> Result.Conflict
                else -> Result.Unavailable
            }
        } finally {
            plaintext.fill(0)
        }
    }

    sealed interface Result {
        data class Ready(val originHash: String, val published: Boolean) : Result

        data object Conflict : Result

        data object Unavailable : Result
    }

    private companion object {
        const val NONCE_BYTES = 32
    }
}
