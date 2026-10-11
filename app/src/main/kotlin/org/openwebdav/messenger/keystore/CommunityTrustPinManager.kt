package org.openwebdav.messenger.keystore

import org.openwebdav.messenger.governance.CommunityGenesisCodec
import org.openwebdav.messenger.governance.CommunityTrustRecord
import org.openwebdav.messenger.identity.IdentityCrypto

internal class CommunityTrustPinManager(
    private val persistence: CommunityTrustPersistence,
) {
    fun pin(
        communityId: String,
        genesisBytes: ByteArray,
        crypto: IdentityCrypto,
        source: TrustPinSource,
    ): TrustPinResult =
        CommunityTrustTransactions.withLock {
            val genesis =
                CommunityGenesisCodec.verify(genesisBytes, communityId, crypto)
                    ?: return@withLock TrustPinResult.INVALID_GENESIS
            val candidate =
                CommunityTrustRecord(
                    communityId,
                    CommunityGenesisCodec.contentAddress(genesisBytes),
                    genesisBytes.copyOf(),
                    genesis.hostSigningPublicKey,
                    genesis.hostBoxPublicKey,
                    source != TrustPinSource.OBSERVED,
                    0,
                    null,
                )
            val records = persistence.load().toMutableList()
            val old = records.singleOrNull { it.communityId == communityId }
            if (old != null && !samePin(old, candidate)) return@withLock TrustPinResult.CONFLICT
            if (old == null) {
                records.add(candidate)
                persistence.save(records)
                return@withLock TrustPinResult.PINNED
            }
            if (!old.fingerprintVerified && candidate.fingerprintVerified) {
                records[records.indexOf(old)] = old.copy(fingerprintVerified = true)
                persistence.save(records)
                return@withLock TrustPinResult.PINNED
            }
            TrustPinResult.ALREADY_PINNED
        }

    private fun samePin(
        left: CommunityTrustRecord,
        right: CommunityTrustRecord,
    ): Boolean =
        left.genesisHash == right.genesisHash && left.hostSigningPublicKey.contentEquals(right.hostSigningPublicKey) &&
            left.hostBoxPublicKey.contentEquals(right.hostBoxPublicKey)
}
