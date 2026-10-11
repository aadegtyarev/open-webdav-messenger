package org.openwebdav.messenger.keystore

import org.openwebdav.messenger.governance.CommunityTrustRecord
import org.openwebdav.messenger.governance.PolicyRevisionDecision
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal interface CommunityTrustPersistence {
    fun load(): List<CommunityTrustRecord>

    fun save(records: List<CommunityTrustRecord>)
}

/** Serializes every trust-store read/modify/write across independently constructed store instances. */
internal object CommunityTrustTransactions {
    private val lock = ReentrantLock()

    fun <T> withLock(block: () -> T): T = lock.withLock(block)

    fun acceptPolicyRevision(
        persistence: CommunityTrustPersistence,
        communityId: String,
        revision: Long,
        policyHash: String,
    ): PolicyRevisionDecision =
        withLock {
            val records = persistence.load().toMutableList()
            val index = records.indexOfFirst { it.communityId == communityId }
            if (index < 0 || !records[index].fingerprintVerified) return@withLock PolicyRevisionDecision.UntrustedHost
            if (revision <= 0 || !POLICY_HASH.matches(policyHash)) return@withLock PolicyRevisionDecision.Invalid
            val old = records[index]
            if (revision < old.highestPolicyRevision) return@withLock PolicyRevisionDecision.Rollback
            if (revision == old.highestPolicyRevision) {
                return@withLock if (old.highestPolicyHash == policyHash) {
                    PolicyRevisionDecision.Duplicate
                } else {
                    PolicyRevisionDecision.Equivocation
                }
            }
            records[index] = old.copy(highestPolicyRevision = revision, highestPolicyHash = policyHash)
            persistence.save(records)
            PolicyRevisionDecision.Accepted
        }

    private val POLICY_HASH = Regex("[a-z2-7]{32}")
}
