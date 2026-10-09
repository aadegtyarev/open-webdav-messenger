package org.openwebdav.messenger.keystore

import android.content.Context
import org.openwebdav.messenger.governance.CommunityTrustRecord
import org.openwebdav.messenger.governance.PolicyRevisionDecision
import org.openwebdav.messenger.identity.IdentityCrypto

internal class CommunityTrustStore(context: Context) {
    private val persistence = EncryptedCommunityTrustStorage(context)
    private val backupRestorer = CommunityTrustBackupRestorer(persistence)
    private val pinManager = CommunityTrustPinManager(persistence)

    @Synchronized
    fun get(communityId: String): CommunityTrustRecord? = persistence.load().singleOrNull { it.communityId == communityId }

    @Synchronized
    fun pinGenesis(
        communityId: String,
        genesisBytes: ByteArray,
        crypto: IdentityCrypto,
        source: TrustPinSource,
    ): TrustPinResult = pinManager.pin(communityId, genesisBytes, crypto, source)

    @Synchronized
    fun restoreRecord(
        communityId: String,
        genesisBytes: ByteArray,
        fingerprintVerified: Boolean,
        policyRevision: Long,
        policyHash: String?,
        crypto: IdentityCrypto,
    ) = backupRestorer.restore(communityId, genesisBytes, fingerprintVerified, policyRevision, policyHash, crypto)

    @Synchronized
    fun remove(communityId: String) = backupRestorer.remove(communityId)

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

    private companion object {
        val HASH = Regex("[a-z2-7]{32}")
    }
}
