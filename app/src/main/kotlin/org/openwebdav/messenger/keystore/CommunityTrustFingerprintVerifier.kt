package org.openwebdav.messenger.keystore

internal class CommunityTrustFingerprintVerifier(
    private val persistence: CommunityTrustPersistence,
) {
    fun verify(
        communityId: String,
        expected: String,
    ): Boolean =
        CommunityTrustTransactions.withLock {
            val records = persistence.load().toMutableList()
            val index = records.indexOfFirst { it.communityId == communityId }
            if (index < 0 || CommunityTrustFingerprint.of(records[index].hostSigningPublicKey) != expected.lowercase()) {
                return@withLock false
            }
            if (!records[index].fingerprintVerified) {
                records[index] = records[index].copy(fingerprintVerified = true)
                persistence.save(records)
            }
            true
        }
}
