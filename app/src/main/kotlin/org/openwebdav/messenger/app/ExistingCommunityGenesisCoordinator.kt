package org.openwebdav.messenger.app

import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.governance.CommunityGenesisCodec
import org.openwebdav.messenger.governance.CommunityGovernanceReader
import org.openwebdav.messenger.governance.CommunityGovernanceWriter
import org.openwebdav.messenger.governance.GovernanceObjectKind
import org.openwebdav.messenger.identity.Identity
import org.openwebdav.messenger.identity.IdentityCrypto
import org.openwebdav.messenger.keystore.TrustPinResult
import org.openwebdav.messenger.transport.WebDavResult
import java.security.SecureRandom

internal class ExistingCommunityGenesisCoordinator(
    private val communityId: String,
    private val communityKey: ChatKey,
    private val identity: Identity,
    private val reader: CommunityGovernanceReader,
    private val writer: CommunityGovernanceWriter,
    private val identityCrypto: IdentityCrypto,
    private val isCurrent: () -> Boolean,
    private val pin: (ByteArray) -> TrustPinResult,
) {
    suspend fun migrate(): GenesisMigrationResult {
        if (!isCurrent()) return GenesisMigrationResult.Unavailable
        val hashes = reader.listHashes(GovernanceObjectKind.GENESIS)
        if (hashes !is WebDavResult.Success) return GenesisMigrationResult.Unavailable
        if (hashes.value.isNotEmpty()) return verifyExisting(hashes.value)

        val nonce = ByteArray(32).also(SecureRandom()::nextBytes)
        val secret = identity.copySignSecret()
        val genesis =
            try {
                CommunityGenesisCodec.sign(
                    communityId,
                    identity.copySignPublic(),
                    identity.copyBoxPublic(),
                    nonce,
                    secret,
                    identityCrypto,
                )
            } finally {
                nonce.fill(0)
                secret.fill(0)
            }
        return try {
            val reference = writer.seal(GovernanceObjectKind.GENESIS, communityId, communityKey, genesis)
            if (writer.publish(reference) !is WebDavResult.Success || !isCurrent()) {
                GenesisMigrationResult.Unavailable
            } else {
                pinResult(pin(genesis), GenesisMigrationResult.Published)
            }
        } finally {
            genesis.fill(0)
        }
    }

    private suspend fun verifyExisting(hashes: List<String>): GenesisMigrationResult {
        if (hashes.size != 1) return GenesisMigrationResult.Conflict
        val stored = reader.fetch(GovernanceObjectKind.GENESIS, communityId, hashes.single(), communityKey)
        if (stored !is WebDavResult.Success) return GenesisMigrationResult.Conflict
        val genesis = CommunityGenesisCodec.verify(stored.value, communityId, identityCrypto) ?: return GenesisMigrationResult.Conflict
        if (!genesis.hostSigningPublicKey.contentEquals(identity.copySignPublic()) ||
            !genesis.hostBoxPublicKey.contentEquals(identity.copyBoxPublic())
        ) {
            return GenesisMigrationResult.Conflict
        }
        if (!isCurrent()) return GenesisMigrationResult.Unavailable
        return pinResult(pin(stored.value), GenesisMigrationResult.AlreadyPublished)
    }

    private fun pinResult(
        result: TrustPinResult,
        success: GenesisMigrationResult,
    ): GenesisMigrationResult =
        when (result) {
            TrustPinResult.PINNED, TrustPinResult.ALREADY_PINNED -> success
            else -> GenesisMigrationResult.Conflict
        }
}
