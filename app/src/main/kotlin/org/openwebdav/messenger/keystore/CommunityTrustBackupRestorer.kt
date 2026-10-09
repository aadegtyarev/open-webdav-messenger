package org.openwebdav.messenger.keystore

import org.openwebdav.messenger.governance.CommunityGenesisCodec
import org.openwebdav.messenger.governance.CommunityTrustRecord
import org.openwebdav.messenger.identity.IdentityCrypto

internal class CommunityTrustBackupRestorer(
    private val persistence: CommunityTrustPersistence,
) {
    fun restore(
        communityId: String,
        genesisBytes: ByteArray,
        fingerprintVerified: Boolean,
        policyRevision: Long,
        policyHash: String?,
        crypto: IdentityCrypto,
    ) {
        require(policyRevision >= 0 && ((policyRevision == 0L) == (policyHash == null)))
        require(policyHash == null || HASH.matches(policyHash))
        val genesis = CommunityGenesisCodec.verify(genesisBytes, communityId, crypto) ?: error("Invalid backed-up genesis")
        val candidate =
            CommunityTrustRecord(
                communityId,
                CommunityGenesisCodec.contentAddress(genesisBytes),
                genesisBytes.copyOf(),
                genesis.hostSigningPublicKey,
                genesis.hostBoxPublicKey,
                fingerprintVerified,
                policyRevision,
                policyHash,
            )
        val records = persistence.load().toMutableList()
        val old = records.singleOrNull { it.communityId == communityId }
        require(old == null || samePin(old, candidate)) { "Backed-up host conflicts with an existing trust pin" }
        val restored =
            if (old == null) {
                candidate
            } else {
                require(policyRevision != old.highestPolicyRevision || policyHash == old.highestPolicyHash)
                old.copy(
                    fingerprintVerified = old.fingerprintVerified,
                    highestPolicyRevision = maxOf(old.highestPolicyRevision, policyRevision),
                    highestPolicyHash = if (policyRevision > old.highestPolicyRevision) policyHash else old.highestPolicyHash,
                )
            }
        records.removeAll { it.communityId == communityId }
        records.add(restored)
        persistence.save(records)
    }

    fun remove(communityId: String) {
        val records = persistence.load()
        if (records.none { it.communityId == communityId }) return
        persistence.save(records.filterNot { it.communityId == communityId })
    }

    private fun samePin(
        left: CommunityTrustRecord,
        right: CommunityTrustRecord,
    ): Boolean =
        left.genesisHash == right.genesisHash && left.hostSigningPublicKey.contentEquals(right.hostSigningPublicKey) &&
            left.hostBoxPublicKey.contentEquals(right.hostBoxPublicKey)

    private companion object {
        val HASH = Regex("[a-z2-7]{32}")
    }
}
