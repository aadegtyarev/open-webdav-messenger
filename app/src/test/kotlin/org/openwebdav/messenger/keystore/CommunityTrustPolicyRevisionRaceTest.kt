package org.openwebdav.messenger.keystore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openwebdav.messenger.governance.CommunityTrustRecord
import org.openwebdav.messenger.governance.PolicyRevisionDecision
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CommunityTrustPolicyRevisionRaceTest {
    @Test
    fun two_store_instances_preserve_high_water_and_other_community() {
        val firstEnteredLoad = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondEnteredLoad = CountDownLatch(1)
        val primary = record("community-a", 9, "a".repeat(32))
        val other = record("community-b", 4, "d".repeat(32))
        val backing = mutableListOf(primary, other)
        val firstPersistence =
            SharedPersistence(backing) {
                firstEnteredLoad.countDown()
                check(releaseFirst.await(3, TimeUnit.SECONDS))
            }
        val secondPersistence = SharedPersistence(backing, secondEnteredLoad::countDown)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first =
                executor.submit<PolicyRevisionDecision> {
                    CommunityTrustTransactions.acceptPolicyRevision(firstPersistence, "community-a", 10, "b".repeat(32))
                }
            assertTrue(firstEnteredLoad.await(2, TimeUnit.SECONDS))
            val second =
                executor.submit<PolicyRevisionDecision> {
                    CommunityTrustTransactions.acceptPolicyRevision(secondPersistence, "community-a", 11, "c".repeat(32))
                }
            assertFalse(secondEnteredLoad.await(150, TimeUnit.MILLISECONDS))
            releaseFirst.countDown()

            assertEquals(PolicyRevisionDecision.Accepted, first.get(2, TimeUnit.SECONDS))
            assertEquals(PolicyRevisionDecision.Accepted, second.get(2, TimeUnit.SECONDS))
            assertEquals(11L, backing.single { it.communityId == "community-a" }.highestPolicyRevision)
            assertEquals("c".repeat(32), backing.single { it.communityId == "community-a" }.highestPolicyHash)
            assertEquals(other, backing.single { it.communityId == "community-b" })
        } finally {
            releaseFirst.countDown()
            executor.shutdownNow()
        }
    }

    private fun record(
        communityId: String,
        revision: Long,
        hash: String,
    ) = CommunityTrustRecord(
        communityId,
        "z".repeat(32),
        byteArrayOf(1),
        ByteArray(32) { 2 },
        ByteArray(32) { 3 },
        true,
        revision,
        hash,
    )

    private class SharedPersistence(
        private val records: MutableList<CommunityTrustRecord>,
        private val afterLoad: () -> Unit = {},
    ) : CommunityTrustPersistence {
        override fun load(): List<CommunityTrustRecord> {
            afterLoad()
            return records.toList()
        }

        override fun save(records: List<CommunityTrustRecord>) {
            this.records.clear()
            this.records.addAll(records)
        }
    }
}
