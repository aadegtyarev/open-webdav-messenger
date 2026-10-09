package org.openwebdav.messenger.keystore

import android.content.Context
import org.openwebdav.messenger.governance.CommunityGenesisCodec
import org.openwebdav.messenger.governance.CommunityTrustRecord
import org.openwebdav.messenger.governance.PolicyRevisionDecision
import org.openwebdav.messenger.identity.IdentityCrypto

internal class CommunityTrustStore(context: Context) {
    private val persistence = EncryptedCommunityTrustStorage(context)

    @Synchronized
    fun get(communityId: String): CommunityTrustRecord? = persistence.load().singleOrNull { it.communityId == communityId }

    @Synchronized
    fun pinGenesis(
        communityId: String,
        genesisBytes: ByteArray,
        crypto: IdentityCrypto,
        source: TrustPinSource,
    ): TrustPinResult {
        val genesis = CommunityGenesisCodec.verify(genesisBytes, communityId, crypto) ?: return TrustPinResult.INVALID_GENESIS
        val candidate =
            CommunityTrustRecord(
                communityId,
                CommunityGenesisCodec.contentAddress(genesisBytes),
                genesis.hostSigningPublicKey,
                genesis.hostBoxPublicKey,
                source != TrustPinSource.OBSERVED,
                0,
                null,
            )
        val records = persistence.load().toMutableList()
        val old = records.singleOrNull { it.communityId == communityId }
        if (old != null && !samePin(old, candidate)) return TrustPinResult.CONFLICT
        if (old == null) {
            records.add(candidate)
            persistence.save(records)
            return TrustPinResult.PINNED
        }
        if (!old.fingerprintVerified && candidate.fingerprintVerified) {
            records[records.indexOf(old)] = old.copy(fingerprintVerified = true)
            persistence.save(records)
            return TrustPinResult.PINNED
        }
        return TrustPinResult.ALREADY_PINNED
    }

    @Synchronized
    fun verifyFingerprint(
        communityId: String,
        expected: String,
    ): Boolean {
        val records = persistence.load().toMutableList()
        val index = records.indexOfFirst { it.communityId == communityId }
        if (index < 0 || CommunityTrustFingerprint.of(records[index].hostSigningPublicKey) != expected.lowercase()) return false
        if (!records[index].fingerprintVerified) {
            records[index] = records[index].copy(fingerprintVerified = true)
            persistence.save(records)
        }
        return true
    }

    @Synchronized
    fun acceptPolicyRevision(
        communityId: String,
        revision: Long,
        policyHash: String,
    ): PolicyRevisionDecision {
        val records = persistence.load().toMutableList()
        val index = records.indexOfFirst { it.communityId == communityId }
        if (index < 0 || !records[index].fingerprintVerified) return PolicyRevisionDecision.UntrustedHost
        if (revision <= 0 || !HASH.matches(policyHash)) return PolicyRevisionDecision.Invalid
        val old = records[index]
        if (revision < old.highestPolicyRevision) return PolicyRevisionDecision.Rollback
        if (revision == old.highestPolicyRevision) {
            return if (old.highestPolicyHash == policyHash) PolicyRevisionDecision.Duplicate else PolicyRevisionDecision.Equivocation
        }
        records[index] = old.copy(highestPolicyRevision = revision, highestPolicyHash = policyHash)
        persistence.save(records)
        return PolicyRevisionDecision.Accepted
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
