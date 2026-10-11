package org.openwebdav.messenger.keystore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openwebdav.messenger.governance.CommunityGenesisCodec
import org.openwebdav.messenger.governance.CommunityTrustRecord
import org.openwebdav.messenger.identity.IdentityTestSupport
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CommunityTrustTransactionsTest {
    @Test
    fun independent_store_instances_serialize_read_modify_write() {
        val crypto = IdentityTestSupport.identityCrypto()
        val host = crypto.generateIdentity()
        val key = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondLoaded = CountDownLatch(1)
        val backing = mutableListOf<CommunityTrustRecord>()
        val firstPersistence =
            SharedPersistence(backing) {
                key.countDown()
                check(releaseFirst.await(3, TimeUnit.SECONDS))
            }
        val secondPersistence = SharedPersistence(backing, secondLoaded::countDown)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first =
                executor.submit<TrustPinResult> {
                    CommunityTrustPinManager(
                        firstPersistence,
                    ).pin("community-a", genesis("community-a", host, crypto), crypto, TrustPinSource.GOVERNANCE_INVITE)
                }
            assertTrue(key.await(2, TimeUnit.SECONDS))
            val secondStarted = CountDownLatch(1)
            val second =
                executor.submit<TrustPinResult> {
                    secondStarted.countDown()
                    CommunityTrustPinManager(
                        secondPersistence,
                    ).pin("community-b", genesis("community-b", host, crypto), crypto, TrustPinSource.GOVERNANCE_INVITE)
                }
            assertTrue(secondStarted.await(2, TimeUnit.SECONDS))
            assertFalse(secondLoaded.await(150, TimeUnit.MILLISECONDS))
            releaseFirst.countDown()

            assertEquals(TrustPinResult.PINNED, first.get(2, TimeUnit.SECONDS))
            assertEquals(TrustPinResult.PINNED, second.get(2, TimeUnit.SECONDS))
            assertEquals(setOf("community-a", "community-b"), backing.map { it.communityId }.toSet())
        } finally {
            releaseFirst.countDown()
            executor.shutdownNow()
        }
    }

    private fun genesis(
        communityId: String,
        host: org.openwebdav.messenger.identity.Identity,
        crypto: org.openwebdav.messenger.identity.IdentityCrypto,
    ) = CommunityGenesisCodec.sign(
        communityId,
        host.copySignPublic(),
        host.copyBoxPublic(),
        ByteArray(32),
        host.copySignSecret(),
        crypto,
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
