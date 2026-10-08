package org.openwebdav.messenger.app

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.openwebdav.messenger.membership.PrivateClaimPublicationStatus
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OnboardingPrivateMembershipIntegrationTest {
    @Test
    fun private_join_publishes_self_and_returns_verified_roster_after_replacement() =
        runTest {
            val fixture = OnboardingPrivateMembershipFixture(ApplicationProvider.getApplicationContext())
            try {
                val generation = org.openwebdav.messenger.account.AccountMutationBarrier.process.replacementGeneration()
                assertTrue(fixture.join() is OnboardingService.JoinResult.Joined)
                assertEquals(generation + 1, fixture.deps.generationAtReconfigure)
                assertEquals(PrivateClaimPublicationStatus.UPLOADED, fixture.deps.status)
                val roster = fixture.service.read(fixture.chatId, fixture.deps.chatKeyStore.load(fixture.chatId)!!, null)
                assertFalse(roster.listingFailed)
                assertEquals(listOf(""), roster.members.map { it.displayName })
            } finally {
                fixture.close()
            }
        }

    @Test
    fun private_join_stays_pending_only_when_remote_put_fails() =
        runTest {
            val fixture = OnboardingPrivateMembershipFixture(ApplicationProvider.getApplicationContext(), failPut = true)
            try {
                assertTrue(fixture.join() is OnboardingService.JoinResult.Joined)
                assertEquals(PrivateClaimPublicationStatus.PENDING, fixture.deps.status)
                val pending = fixture.store.load(fixture.chatId, fixture.chatId, "private", fixture.key, fixture.identity)
                assertEquals(false, pending?.uploaded)
            } finally {
                fixture.close()
            }
        }
}
