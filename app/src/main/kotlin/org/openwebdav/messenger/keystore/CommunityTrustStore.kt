package org.openwebdav.messenger.keystore

import android.content.Context
import org.openwebdav.messenger.governance.CommunityTrustRecord
import org.openwebdav.messenger.governance.PolicyRevisionDecision
import org.openwebdav.messenger.identity.IdentityCrypto

internal interface CommunityTrustBackupStore {
    fun restoreRecord(
        communityId: String,
        genesisBytes: ByteArray,
        fingerprintVerified: Boolean,
        policyRevision: Long,
        policyHash: String?,
        crypto: IdentityCrypto,
    )

    fun remove(communityId: String)
}

internal class CommunityTrustStore(context: Context) : CommunityTrustBackupStore {
    private val persistence = EncryptedCommunityTrustStorage(context)
    private val backupRestorer = CommunityTrustBackupRestorer(persistence)
    private val pinManager = CommunityTrustPinManager(persistence)
    private val fingerprintVerifier = CommunityTrustFingerprintVerifier(persistence)

    fun get(communityId: String): CommunityTrustRecord? =
        CommunityTrustTransactions.withLock { persistence.load().singleOrNull { it.communityId == communityId } }

    @Synchronized
    fun pinGenesis(
        communityId: String,
        genesisBytes: ByteArray,
        crypto: IdentityCrypto,
        source: TrustPinSource,
    ): TrustPinResult = pinManager.pin(communityId, genesisBytes, crypto, source)

    @Synchronized
    override fun restoreRecord(
        communityId: String,
        genesisBytes: ByteArray,
        fingerprintVerified: Boolean,
        policyRevision: Long,
        policyHash: String?,
        crypto: IdentityCrypto,
    ) = backupRestorer.restore(communityId, genesisBytes, fingerprintVerified, policyRevision, policyHash, crypto)

    @Synchronized
    override fun remove(communityId: String) = backupRestorer.remove(communityId)

    @Synchronized
    fun verifyFingerprint(
        communityId: String,
        expected: String,
    ): Boolean = fingerprintVerifier.verify(communityId, expected)

    fun acceptPolicyRevision(
        communityId: String,
        revision: Long,
        policyHash: String,
    ): PolicyRevisionDecision = CommunityTrustTransactions.acceptPolicyRevision(persistence, communityId, revision, policyHash)
}
