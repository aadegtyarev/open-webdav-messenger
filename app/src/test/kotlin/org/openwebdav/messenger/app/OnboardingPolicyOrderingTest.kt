package org.openwebdav.messenger.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.openwebdav.messenger.account.AccountMutationBarrier

class OnboardingPolicyOrderingTest {
    @Test
    fun delayed_onboarding_defaults_cannot_overwrite_newer_policy_or_replaced_runtime() =
        runTest {
            val coordinator = CommunityPolicyCoordinator()
            val communityId = "community-a"
            val defaults = CommunityPolicy(14, 60)
            var generation = "runtime-old"
            var remote = defaults
            var rosterWrites = 0
            val initial = coordinator.defaults(communityId)
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val onboarding =
                launch {
                    coordinator.runInitialWrites(
                        initial,
                        generation,
                        { generation },
                        writeRoster = { rosterWrites++ },
                        writePolicy = { policy ->
                            started.complete(Unit)
                            release.await()
                            remote = policy
                            true
                        },
                        commitPolicy = {},
                    )
                }
            started.await()
            val newer = coordinator.retention(communityId, defaults, 30)
            val settings =
                launch {
                    AccountMutationBarrier.process.withExclusive {
                        if (coordinator.isLatest(newer)) {
                            remote = newer.policy
                            coordinator.complete(newer)
                        }
                    }
                }
            release.complete(Unit)
            onboarding.join()
            settings.join()
            assertEquals(CommunityPolicy(30, 60), remote)
            assertEquals(1, rosterWrites)

            val stale = coordinator.defaults(communityId)
            var staleWrites = 0
            var staleRosterWrites = 0
            val staleTask =
                launch {
                    coordinator.runInitialWrites(
                        stale,
                        "runtime-old",
                        { generation },
                        writeRoster = { staleRosterWrites++ },
                        writePolicy = {
                            staleWrites++
                            true
                        },
                        commitPolicy = {},
                    )
                }
            AccountMutationBarrier.process.withExclusive {
                coordinator.reset()
                generation = "runtime-restored"
            }
            staleTask.join()
            assertEquals(0, staleWrites)
            assertEquals(0, staleRosterWrites)
            assertEquals(CommunityPolicy(30, 60), remote)
        }
}
